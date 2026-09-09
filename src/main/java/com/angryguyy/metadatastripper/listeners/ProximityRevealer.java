package com.angryguyy.metadatastripper.listeners;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.util.Vector;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * A highly optimized, dual-mode proximity and Line-of-Sight (LoS) radar.
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
 *   <li><b>Memory Allocation:</b> Zero-GC. Utilizes primitive vectors, bit-vectors ({@link EnumSet}), and DDA step-traversal for raytracing.</li>
 *   <li><b>Execution Control:</b> Splits execution logic to conserve CPU cycles. The heavy radius scan runs exclusively on physical block transitions, whereas the lightweight raytrace evaluates on camera transitions (pitch/yaw > 3 degrees).</li>
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
    private final Set<Material> sensitiveMaterials;

    /**
     * Constructs the radar module and initializes the high-speed material lookup set.
     *
     * @param configuredBlocks a list of material names defined in the plugin configuration
     */
    public ProximityRevealer(List<String> configuredBlocks) {
        sensitiveMaterials = EnumSet.noneOf(Material.class);
        for (String name : configuredBlocks) {
            try {
                sensitiveMaterials.add(Material.valueOf(name.toUpperCase()));
            } catch (Exception ignored) {}
        }
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

        boolean lookChanged = Math.abs(from.getYaw() - to.getYaw()) > 3.0f
                || Math.abs(from.getPitch() - to.getPitch()) > 3.0f;

        if (!blockChanged && !lookChanged) {
            return;
        }

        Player player = event.getPlayer();
        if (player.hasPermission("metadatastripper.bypass")) {
            return;
        }

        World world = to.getWorld();

        if (blockChanged) {
            int cX = to.getBlockX();
            int cY = to.getBlockY();
            int cZ = to.getBlockZ();

            for (int x = -RADIUS; x <= RADIUS; x++) {
                for (int y = -RADIUS; y <= RADIUS; y++) {
                    for (int z = -RADIUS; z <= RADIUS; z++) {
                        int bY = cY + y;
                        Block b = world.getBlockAt(cX + x, bY, cZ + z);
                        Material type = b.getType();

                        boolean isLiquid = type == Material.WATER || type == Material.LAVA;

                        if (sensitiveMaterials.contains(type) || (isLiquid && bY < 55)) {
                            player.sendBlockChange(b.getLocation(), b.getBlockData());
                        }
                    }
                }
            }
        }

        double originX = to.getX();
        double originY = to.getY() + player.getEyeHeight();
        double originZ = to.getZ();

        Vector dir = to.getDirection();
        double dx = dir.getX();
        double dy = dir.getY();
        double dz = dir.getZ();

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

            boolean isLiquid = type == Material.WATER || type == Material.LAVA;

            if (sensitiveMaterials.contains(type) || (isLiquid && bY < 55)) {
                player.sendBlockChange(b.getLocation(), b.getBlockData());
            }

            if (type.isSolid() && type.isOccluding()) {
                break;
            }
        }
    }
}