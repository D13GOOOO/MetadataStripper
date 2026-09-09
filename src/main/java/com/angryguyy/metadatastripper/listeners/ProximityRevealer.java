package com.angryguyy.metadatastripper.listeners;

import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerMoveEvent;

/**
 * A bounded, dual-mode proximity and Line-of-Sight (LoS) radar.
 * <p>
 * Because the low-level network interceptor applies a zero-trust obfuscation policy to all
 * configured blocks (including air-exposed ores and underground liquids), this class acts as
 * the bridge to preserve legitimate gameplay. It implements a spherical close-range scan
 * combined with an occlusion-culled raytrace algorithm. This ensures legitimate players can
 * seamlessly explore caves and see deep ravine bottoms (e.g., for MLG water drops) without
 * exposing hidden ores behind solid walls to Freecam or X-Ray users.
 * <p>
 * <b>Architectural Notes:</b>
 * <ul>
 *   <li><b>Execution Context:</b> Executes synchronously on the player's owning Region Thread (Folia)
 *       or the main server thread (Paper) during the {@link PlayerMoveEvent} phase.</li>
 *   <li><b>Thread Safety:</b> Designed to handle concurrent administrative reloads. The material lookup
 *       set is marked as {@code volatile} and is strictly immutable, ensuring safe reads across regional threads.</li>
 * </ul>
 * <p>
 * <b>Algorithmic Complexity & Optimizations:</b>
 * <ul>
 *   <li><b>Full Scan:</b> O(11³) execution. Reserved exclusively for first entry, teleports, and world changes.</li>
 *   <li><b>Shell Scan:</b> Drastically reduced complexity for standard one-block movement. It computes and scans
 *       only the newly entered dimensional planes (the "shell" of the radius) rather than the entire volume.</li>
 *   <li><b>Raytrace:</b> Occlusion-culled. The ray stops immediately upon hitting a solid block, preventing
 *       computation waste and maintaining strict anti-xray integrity behind walls. Evaluated only on camera transitions (> 3 degrees).</li>
 * </ul>
 */
public final class ProximityRevealer implements Listener {

    /**
     * The cubic radius (in blocks) around the player to scan and reveal.
     * <p>
     * Dynamically configurable to balance performance and legitimate interaction range.
     */
    private volatile int radius = 5;

    /**
     * The maximum distance (in blocks) the Line-of-Sight raytrace will travel.
     * <p>
     * Dynamically configurable to cover the visual depth of standard ravines and large cave systems.
     */
    private volatile int raytraceMaxDistance = 45;

    /**
     * A highly optimized bit-vector containing all materials that require dynamic revealing.
     * <p>
     * <b>Thread Safety:</b> Marked as {@code volatile} to guarantee visibility across multiple Region Schedulers
     * when the {@code /ms reload} command atomically replaces this set from the global thread.
     */
    private volatile Set<Material> sensitiveMaterials;

    /**
     * A lock-free map storing the last known block coordinates of every online player to calculate delta movements.
     */
    private final Map<UUID, ScanPosition> scanPositions = new ConcurrentHashMap<>();

    /**
     * Constructs the radar module and initializes the high-speed material lookup set.
     *
     * @param configuredBlocks a list of sensitive material names defined in the plugin configuration
     */
    public ProximityRevealer(List<String> configuredBlocks) {
        updateConfiguredBlocks(configuredBlocks);
    }

    /**
     * Updates the dynamic proximity and raytrace limits from the configuration.
     *
     * @param radius              the new spherical scan radius
     * @param raytraceMaxDistance the new maximum line-of-sight distance
     */
    public void updateSettings(int radius, int raytraceMaxDistance) {
        this.radius = radius;
        this.raytraceMaxDistance = raytraceMaxDistance;
    }

    /**
     * Atomically replaces the material lookup table used by proximity reveals.
     * <p>
     * This method safely converts the string list into an immutable, highly optimized {@link EnumSet}.
     * Invalid materials are silently ignored, as validation is expected to happen upstream via
     * {@link com.angryguyy.metadatastripper.config.ConfigurationValidator}.
     *
     * @param configuredBlocks material names from the current active plugin configuration
     */
    public void updateConfiguredBlocks(List<String> configuredBlocks) {
        Set<Material> updatedMaterials = EnumSet.noneOf(Material.class);
        for (String name : configuredBlocks) {
            try {
                updatedMaterials.add(Material.valueOf(name.toUpperCase()));
            } catch (Exception ignored) {}
        }
        sensitiveMaterials = Collections.unmodifiableSet(updatedMaterials);
    }

    /**
     * Intercepts player movement to evaluate and dynamically reveal nearby obfuscated blocks.
     * <p>
     * Operates at the {@link EventPriority#MONITOR} priority to ensure movement wasn't cancelled
     * by anti-cheats or territory protection plugins. Bypass permission holders skip this logic entirely.
     *
     * @param event the native {@link PlayerMoveEvent} dispatched by the server
     */
    @SuppressWarnings("unused")
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerMove(PlayerMoveEvent event) {
        Location from = event.getFrom();
        Location to = event.getTo();

        boolean blockChanged = from.getBlockX() != to.getBlockX()
                || from.getBlockY() != to.getBlockY()
                || from.getBlockZ() != to.getBlockZ();

        boolean lookChanged = angleDifference(from.getYaw(), to.getYaw()) > 3.0f
                || Math.abs(from.getPitch() - to.getPitch()) > 3.0f;

        if (!blockChanged && !lookChanged) {
            return;
        }

        Player player = event.getPlayer();
        if (player.hasPermission("metadatastripper.bypass")) {
            return;
        }

        World world = to.getWorld();
        UUID playerUuid = player.getUniqueId();

        if (blockChanged) {
            int cX = to.getBlockX();
            int cY = to.getBlockY();
            int cZ = to.getBlockZ();
            ScanPosition previous = scanPositions.put(playerUuid, new ScanPosition(world, cX, cY, cZ));

            if (previous == null || previous.world() != world
                    || Math.abs(previous.x() - cX) > 1
                    || Math.abs(previous.y() - cY) > 1
                    || Math.abs(previous.z() - cZ) > 1) {
                scanCube(player, world, cX, cY, cZ);
            } else {
                scanEnteringShell(player, world, previous, cX, cY, cZ);
            }
        }

        double originX = to.getX();
        double originY = to.getY() + player.getEyeHeight();
        double originZ = to.getZ();

        double yaw = Math.toRadians(to.getYaw());
        double pitch = Math.toRadians(to.getPitch());
        double horizontal = Math.cos(pitch);
        double dx = -Math.sin(yaw) * horizontal;
        double dy = -Math.sin(pitch);
        double dz = Math.cos(yaw) * horizontal;

        int lastX = Integer.MAX_VALUE;
        int lastY = Integer.MAX_VALUE;
        int lastZ = Integer.MAX_VALUE;

        for (double t = 0; t <= raytraceMaxDistance; t += 0.5) {
            int bX = (int) Math.floor(originX + dx * t);
            int bY = (int) Math.floor(originY + dy * t);
            int bZ = (int) Math.floor(originZ + dz * t);

            if (bX == lastX && bY == lastY && bZ == lastZ) {
                continue;
            }

            lastX = bX;
            lastY = bY;
            lastZ = bZ;

            Block b = world.getBlockAt(bX, bY, bZ);
            Material type = b.getType();

            revealIfSensitive(player, b, type, bY);

            if (type.isSolid() && type.isOccluding()) {
                break;
            }
        }
    }

    /**
     * Scans the complete cubic reveal volume.
     * <p>
     * Due to its O(R³) complexity, this is strictly reserved for high-delta movements
     * such as teleports, world changes, respawns, or initial joins.
     *
     * @param player  recipient of the targeted block updates
     * @param world   the world containing the scan volume
     * @param centerX center block X coordinate
     * @param centerY center block Y coordinate
     * @param centerZ center block Z coordinate
     */
    private void scanCube(Player player, World world, int centerX, int centerY, int centerZ) {
        for (int x = -radius; x <= radius; x++) {
            for (int y = -radius; y <= radius; y++) {
                for (int z = -radius; z <= radius; z++) {
                    Block block = world.getBlockAt(centerX + x, centerY + y, centerZ + z);
                    revealIfSensitive(player, block, block.getType(), centerY + y);
                }
            }
        }
    }

    /**
     * Dynamically scans only the planes (shell) that newly entered the reveal volume during a one-block move.
     * <p>
     * This mathematical optimization dramatically reduces the number of block lookups required
     * during normal walking/sprinting, vastly lowering the CPU footprint on the Region Scheduler.
     *
     * @param player  recipient of the targeted block updates
     * @param world   the world containing the scan volume
     * @param previous the previous scan center, used to calculate the directional delta
     * @param centerX current center block X coordinate
     * @param centerY current center block Y coordinate
     * @param centerZ current center block Z coordinate
     */
    private void scanEnteringShell(Player player, World world, ScanPosition previous, int centerX, int centerY, int centerZ) {
        if (previous.x() != centerX) {
            int x = centerX + (centerX > previous.x() ? radius : -radius);
            for (int y = -radius; y <= radius; y++) {
                for (int z = -radius; z <= radius; z++) {
                    Block block = world.getBlockAt(x, centerY + y, centerZ + z);
                    revealIfSensitive(player, block, block.getType(), centerY + y);
                }
            }
        }
        if (previous.y() != centerY) {
            int y = centerY + (centerY > previous.y() ? radius : -radius);
            for (int x = -radius; x <= radius; x++) {
                for (int z = -radius; z <= radius; z++) {
                    Block block = world.getBlockAt(centerX + x, y, centerZ + z);
                    revealIfSensitive(player, block, block.getType(), y);
                }
            }
        }
        if (previous.z() != centerZ) {
            int z = centerZ + (centerZ > previous.z() ? radius : -radius);
            for (int x = -radius; x <= radius; x++) {
                for (int y = -radius; y <= radius; y++) {
                    Block block = world.getBlockAt(centerX + x, centerY + y, z);
                    revealIfSensitive(player, block, block.getType(), centerY + y);
                }
            }
        }
    }

    /**
     * Evaluates a block and dispatches a single-block update packet directly to the client
     * if the block matches the sensitive material profile.
     * <p>
     * Includes dedicated logic for underground liquids (Y < 55) to support deep-cave exploration
     * and MLG mechanics, which are aggressively obfuscated by the engine's Engine Mode 2.
     *
     * @param player recipient of the block update
     * @param block  the physical block being evaluated
     * @param type   the natively cached material of the block
     * @param y      the vertical Y coordinate of the block (used for depth heuristics)
     */
    private void revealIfSensitive(Player player, Block block, Material type, int y) {
        boolean isLiquid = type == Material.WATER || type == Material.LAVA;

        if (sensitiveMaterials.contains(type) || (isLiquid && y < 55)) {
            player.sendBlockChange(block.getLocation(), block.getBlockData());
        }
    }

    /**
     * Computes the shortest absolute angular distance between two yaw values.
     * <p>
     * Used to filter out micro-jitters in player aiming and only trigger the raytrace
     * logic for intentional, significant camera movements.
     *
     * @param first  the initial yaw value in degrees
     * @param second the subsequent yaw value in degrees
     * @return the normalized angular distance between 0.0 and 180.0 degrees
     */
    private float angleDifference(float first, float second) {
        float difference = Math.abs(first - second) % 360.0f;
        return difference > 180.0f ? 360.0f - difference : difference;
    }

    /**
     * Safely evicts cached movement states from memory when a player disconnects.
     * <p>
     * Prevents memory leaks by ensuring the {@link ConcurrentHashMap} does not hold
     * orphaned references to UUIDs or Worlds.
     *
     * @param event the native player quit event
     */
    @EventHandler
    public void onPlayerQuit(org.bukkit.event.player.PlayerQuitEvent event) {
        scanPositions.remove(event.getPlayer().getUniqueId());
    }

    /**
     * Safely performs garbage collection by sweeping orphaned UUID profiles.
     * <p>
     * Designed to be called by a low-priority global scheduler task to ensure
     * disconnected players or fake NPC entities do not cause memory leaks
     * if they bypass the standard PlayerQuitEvent.
     *
     * @param activeUuids a set of currently online and valid player UUIDs
     */
    public void cleanOrphans(Set<UUID> activeUuids) {
        scanPositions.keySet().removeIf(uuid -> !activeUuids.contains(uuid));
    }

    /**
     * An immutable data carrier representing a player's last known quantized block position.
     * Used exclusively to calculate delta movements for optimized shell scanning.
     *
     * @param world the Bukkit World reference
     * @param x     the quantized X coordinate
     * @param y     the quantized Y coordinate
     * @param z     the quantized Z coordinate
     */
    private record ScanPosition(World world, int x, int y, int z) {
    }
}