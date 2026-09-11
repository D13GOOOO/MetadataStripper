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
 */
public final class ProximityRevealer implements Listener {

    /** The spherical scan radius (in blocks) around the player to reveal blocks. */
    private volatile int radius = 5;

    /** The maximum distance (in blocks) the Line-of-Sight raytrace will travel. */
    private volatile int raytraceMaxDistance = 45;

    /** The operational engine mode determining obfuscation intensity. */
    private volatile int engineMode = 2;

    /** The Y-level threshold below which solid fill cave obfuscation is active. */
    private volatile int aggressiveYMax = 5;

    /** A volatile, immutable set of sensitive materials requiring proximity reveals. */
    private volatile Set<Material> sensitiveMaterials;

    /** Lock-free cache tracking the last known block coordinates of online players for shell scanning delta calculations. */
    private final Map<UUID, ScanPosition> scanPositions = new ConcurrentHashMap<>();

    /**
     * Constructs the proximity revealer and initializes the immutable material set.
     *
     * @param configuredBlocks a list of sensitive block material names from configuration
     */
    public ProximityRevealer(List<String> configuredBlocks) {
        updateConfiguredBlocks(configuredBlocks);
    }

    /**
     * Dynamically updates the proximity radius, raytrace distance, engine mode, and subterranean fill threshold.
     *
     * @param radius              the new spherical scan radius
     * @param raytraceMaxDistance the new maximum line-of-sight distance
     * @param engineMode          the current engine mode (e.g., 1 or 2)
     * @param aggressiveYMax      the vertical Y-level threshold for aggressive fill
     */
    public void updateSettings(int radius, int raytraceMaxDistance, int engineMode, int aggressiveYMax) {
        this.radius = radius;
        this.raytraceMaxDistance = raytraceMaxDistance;
        this.engineMode = engineMode;
        this.aggressiveYMax = aggressiveYMax;
    }

    /**
     * Atomically rebuilds and replaces the internal thread-safe set of sensitive materials.
     *
     * @param configuredBlocks material names parsed from configuration
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
     * Intercepts player movement to evaluate and dynamically reveal nearby obfuscated blocks or caves.
     * <p>
     * Evaluates block delta changes and camera orientation shifts to trigger either full volume cube scans,
     * optimized shell scans, or occlusion-culled LoS raytraces.
     *
     * @param event the native {@link PlayerMoveEvent}
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

            // Strictly halt the raytrace upon entering the aggressive underground zone.
            // Prevents the ray from acting as an X-Ray through the artificial Deepslate walls.
            if (engineMode >= 2 && bY < aggressiveYMax) {
                break;
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
     * Scans the complete cubic reveal volume around the player's position.
     * Reserved for teleports, world changes, or large spatial jumps.
     *
     * @param player  the recipient player
     * @param world   the active world instance
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
     * Scans only the newly entered planes (shell) during a standard single-block movement delta.
     * Dramatically reduces CPU overhead compared to full cube scans.
     *
     * @param player   the recipient player
     * @param world    the active world instance
     * @param previous the player's previous quantized position
     * @param centerX  current center block X coordinate
     * @param centerY  current center block Y coordinate
     * @param centerZ  current center block Z coordinate
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
     * if the block needs to be organically revealed from the obfuscated state.
     *
     * @param player recipient of the block update
     * @param block  the physical block being evaluated
     * @param type   the natively cached material of the block
     * @param y      the vertical Y coordinate of the block
     */
    private void revealIfSensitive(Player player, Block block, Material type, int y) {
        // If the player is exploring deep underground in aggressive mode, the client renders a solid block.
        // We must push block updates for Air, Stone, Dirt, etc., so they don't get stuck in fake walls.
        if (engineMode >= 2 && y < aggressiveYMax) {
            Material expectedFake = (y < 0) ? Material.DEEPSLATE : Material.STONE;
            if (type != expectedFake) {
                player.sendBlockChange(block.getLocation(), block.getBlockData());
            }
            return;
        }

        boolean isLiquid = type == Material.WATER || type == Material.LAVA;
        if (sensitiveMaterials.contains(type) || (isLiquid && y < 55)) {
            player.sendBlockChange(block.getLocation(), block.getBlockData());
        }
    }

    /**
     * Computes the absolute shortest angular distance between two yaw orientations.
     *
     * @param first  initial yaw angle in degrees
     * @param second subsequent yaw angle in degrees
     * @return normalized angular difference between 0.0 and 180.0 degrees
     */
    private float angleDifference(float first, float second) {
        float difference = Math.abs(first - second) % 360.0f;
        return difference > 180.0f ? 360.0f - difference : difference;
    }

    /**
     * Purges movement records when a player disconnects to prevent memory leaks.
     *
     * @param event the native player quit event
     */
    @SuppressWarnings("unused")
    @EventHandler
    public void onPlayerQuit(org.bukkit.event.player.PlayerQuitEvent event) {
        scanPositions.remove(event.getPlayer().getUniqueId());
    }

    /**
     * Sweeps orphaned player positions for offline or invalid UUIDs.
     *
     * @param activeUuids a set of currently online player UUIDs
     */
    public void cleanOrphans(Set<UUID> activeUuids) {
        scanPositions.keySet().removeIf(uuid -> !activeUuids.contains(uuid));
    }

    /**
     * Immutable carrier representing a player's last quantized grid location.
     *
     * @param world the Bukkit world reference
     * @param x     the quantized X block coordinate
     * @param y     the quantized Y block coordinate
     * @param z     the quantized Z block coordinate
     */
    private record ScanPosition(World world, int x, int y, int z) {
    }
}