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
 * <b>Algorithmic Complexity:</b>
 * <ul>
 *   <li><b>Memory Allocation:</b> Uses bounded player scan state and Bukkit block update objects.</li>
 *   <li><b>Execution Control:</b> Full scans are reserved for first entry, teleports, and world changes; one-block movement scans only the entering shell, while the raytrace evaluates on camera transitions.</li>
 * </ul>
 */
public final class ProximityRevealer implements Listener {

    /**
     * The cubic radius (in blocks) around the player to scan and reveal.
     * Kept minimal to encompass immediate mining range without straining the main thread.
     */
    private static final int RADIUS = 5;

    /**
     * The maximum distance (in blocks) the Line-of-Sight raytrace will travel.
     * Tuned to cover the visual depth of standard ravines and large cave systems.
     */
    private static final int RAYTRACE_MAX_DISTANCE = 45;

    /**
     * A bit-vector containing all materials that require dynamic revealing.
     * Guarantees O(1) constant-time lookups during intensive triple-nested spatial loops.
     */
    private volatile Set<Material> sensitiveMaterials;
    private final Map<UUID, ScanPosition> scanPositions = new ConcurrentHashMap<>();

    /**
     * Constructs the radar module and initializes the high-speed material lookup set.
     *
     * @param configuredBlocks a list of material names defined in the plugin configuration
     */
    public ProximityRevealer(List<String> configuredBlocks) {
        updateConfiguredBlocks(configuredBlocks);
    }

    /**
     * Replaces the material lookup table used by proximity reveals.
     *
     * @param configuredBlocks material names from the current plugin configuration
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
     * Intercepts player movement to evaluate and reveal nearby obfuscated blocks using
     * both spherical proximity scanning and occlusion-culled raytracing.
     *
     * @param event the native player movement event
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

        for (double t = 0; t <= RAYTRACE_MAX_DISTANCE; t += 0.5) {
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
     * Scans the complete reveal volume after a teleport, world change, or first movement.
     *
     * @param player recipient of block updates
     * @param world world containing the scan volume
     * @param centerX center block X coordinate
     * @param centerY center block Y coordinate
     * @param centerZ center block Z coordinate
     */
    private void scanCube(Player player, World world, int centerX, int centerY, int centerZ) {
        for (int x = -RADIUS; x <= RADIUS; x++) {
            for (int y = -RADIUS; y <= RADIUS; y++) {
                for (int z = -RADIUS; z <= RADIUS; z++) {
                    Block block = world.getBlockAt(centerX + x, centerY + y, centerZ + z);
                    revealIfSensitive(player, block, block.getType(), centerY + y);
                }
            }
        }
    }

    /**
     * Scans only the planes that entered the reveal volume during a one-block move.
     *
     * @param player recipient of block updates
     * @param world world containing the scan volume
     * @param previous previous scan center
     * @param centerX current center block X coordinate
     * @param centerY current center block Y coordinate
     * @param centerZ current center block Z coordinate
     */
    private void scanEnteringShell(Player player, World world, ScanPosition previous, int centerX, int centerY, int centerZ) {
        if (previous.x() != centerX) {
            int x = centerX + (centerX > previous.x() ? RADIUS : -RADIUS);
            for (int y = -RADIUS; y <= RADIUS; y++) {
                for (int z = -RADIUS; z <= RADIUS; z++) {
                    Block block = world.getBlockAt(x, centerY + y, centerZ + z);
                    revealIfSensitive(player, block, block.getType(), centerY + y);
                }
            }
        }
        if (previous.y() != centerY) {
            int y = centerY + (centerY > previous.y() ? RADIUS : -RADIUS);
            for (int x = -RADIUS; x <= RADIUS; x++) {
                for (int z = -RADIUS; z <= RADIUS; z++) {
                    Block block = world.getBlockAt(centerX + x, y, centerZ + z);
                    revealIfSensitive(player, block, block.getType(), y);
                }
            }
        }
        if (previous.z() != centerZ) {
            int z = centerZ + (centerZ > previous.z() ? RADIUS : -RADIUS);
            for (int x = -RADIUS; x <= RADIUS; x++) {
                for (int y = -RADIUS; y <= RADIUS; y++) {
                    Block block = world.getBlockAt(centerX + x, centerY + y, z);
                    revealIfSensitive(player, block, block.getType(), centerY + y);
                }
            }
        }
    }

    /**
     * Sends a block update only when the block belongs to the configured reveal set.
     *
     * @param player recipient of the block update
     * @param block block being evaluated
     * @param type material of the block
     * @param y block Y coordinate
     */
    private void revealIfSensitive(Player player, Block block, Material type, int y) {
        boolean isLiquid = type == Material.WATER || type == Material.LAVA;
        if (sensitiveMaterials.contains(type) || (isLiquid && y < 55)) {
            player.sendBlockChange(block.getLocation(), block.getBlockData());
        }
    }

    /**
     * Computes the shortest absolute angular distance between two yaw values.
     *
     * @param first first yaw value
     * @param second second yaw value
     * @return normalized angular distance in degrees
     */
    private float angleDifference(float first, float second) {
        float difference = Math.abs(first - second) % 360.0f;
        return difference > 180.0f ? 360.0f - difference : difference;
    }

    /**
     * Removes cached movement state when a player disconnects.
     *
     * @param event player quit event
     */
    @EventHandler
    public void onPlayerQuit(org.bukkit.event.player.PlayerQuitEvent event) {
        scanPositions.remove(event.getPlayer().getUniqueId());
    }

    private record ScanPosition(World world, int x, int y, int z) {
    }
}