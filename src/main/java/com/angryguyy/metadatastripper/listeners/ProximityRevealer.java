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

import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * A highly optimized, real-time proximity radar module for dynamically revealing obfuscated environment data.
 * <p>
 * Because the {@code NettyInjector} enforces a zero-trust policy and aggressively obfuscates all targeted
 * blocks before packet serialization (including surface-exposed ores and underground liquids), this listener
 * is required to restore visual integrity for legitimate players exploring on foot. It bypasses network
 * culling limits by sending localized, spoof-reverting block updates exclusively within a close-range radius.
 * <p>
 * <b>Algorithmic Complexity:</b>
 * <ul>
 *   <li><b>Memory Allocation:</b> Utilizes a bit-vector {@link EnumSet} for O(1) constant-time, ultra-low overhead material lookups. Skips calculations on sub-block (pitch/yaw/decimal) movements.</li>
 *   <li><b>Execution Time:</b> O(V) where V is the cubic volume of the scan radius, executing strictly upon whole-block coordinate shifts.</li>
 * </ul>
 */
public final class ProximityRevealer implements Listener {

    /**
     * The cubic radius (in blocks) around the player to scan and reveal.
     * Kept minimal to encompass immediate visual and mining range without straining the server thread.
     */
    private static final int RADIUS = 5;

    private final Set<Material> sensitiveMaterials;

    public ProximityRevealer(List<String> configuredBlocks) {
        sensitiveMaterials = EnumSet.noneOf(Material.class);
        for (String name : configuredBlocks) {
            try {
                sensitiveMaterials.add(Material.valueOf(name.toUpperCase()));
            } catch (Exception ignored) {}
        }
    }

    /**
     * Intercepts player movement to evaluate and reveal nearby obfuscated blocks.
     *
     * @param event the native player movement event
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerMove(PlayerMoveEvent event) {
        Location from = event.getFrom();
        Location to = event.getTo();

        if (from.getBlockX() == to.getBlockX() && from.getBlockY() == to.getBlockY() && from.getBlockZ() == to.getBlockZ()) {
            return;
        }

        Player player = event.getPlayer();
        if (player.hasPermission("metadatastripper.bypass")) {
            return;
        }

        World world = to.getWorld();
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
}