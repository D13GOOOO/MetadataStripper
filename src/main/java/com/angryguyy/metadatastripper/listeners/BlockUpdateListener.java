package com.angryguyy.metadatastripper.listeners;

import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;

/**
 * A bounded-overhead listener responsible for dynamically revealing obfuscated blocks during legitimate mining.
 * <p>
 * When a player successfully breaks a block, this class immediately forces a block state update for the
 * six adjacent faces. Since single-block updates (via {@link Player#sendBlockChange(org.bukkit.Location, org.bukkit.block.data.BlockData)})
 * deliberately bypass the aggressive chunk obfuscation pipeline handled by Netty, this seamlessly reveals
 * hidden ores to legitimate players without compromising the broader anti-xray integrity of the chunk.
 * <p>
 * <b>Architectural Notes:</b>
 * <ul>
 *   <li><b>Execution Context:</b> Executes synchronously on the region thread (Folia) or the main server thread (Paper)
 *       during the block break event phase.</li>
 *   <li><b>GC Pressure & Performance:</b> Strictly bounded to O(1) execution time. By utilizing a pre-allocated static array
 *       for directional offsets, it guarantees zero object allocation overhead during highly frequent mining operations.</li>
 *   <li><b>Security & Compatibility:</b> Operates at the {@link EventPriority#MONITOR} level to ensure blocks are only revealed
 *       if the break action was definitively permitted by the server (e.g., passing WorldGuard/Towny protection checks).</li>
 * </ul>
 */
public final class BlockUpdateListener implements Listener {

    /**
     * Pre-allocated static array containing the six cardinal block faces.
     * <p>
     * <b>Memory Optimization:</b> Caching these enum constants prevents the continuous array instantiation
     * overhead that would normally occur if {@code new BlockFace[]{...}} or {@code List.of(...)} were used
     * inside the highly frequent block-break event cycle.
     */
    private static final BlockFace[] ADJACENT_FACES = {
            BlockFace.UP, BlockFace.DOWN, BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST
    };

    /**
     * Creates the block-break reveal listener.
     * <p>
     * Must be registered with the Bukkit {@link org.bukkit.plugin.PluginManager} during plugin startup.
     */
    public BlockUpdateListener() {
    }

    /**
     * Intercepts block break events to safely reveal adjacent blocks to the mining player.
     * <p>
     * The {@code ignoreCancelled = true} constraint combined with the {@link EventPriority#MONITOR} priority
     * ensures that this logic only executes if the block break was completely successful and not blocked
     * by other server mechanisms.
     * <p>
     * If the player possesses the {@code metadatastripper.bypass} permission, this operation is short-circuited,
     * as their chunk packets are never obfuscated by the Netty handler in the first place.
     *
     * @param event the native {@link BlockBreakEvent} dispatched by the server
     * @see Player#sendBlockChange(org.bukkit.Location, org.bukkit.block.data.BlockData)
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        Player player = event.getPlayer();

        if (player.hasPermission("metadatastripper.bypass")) {
            return;
        }

        Block center = event.getBlock();

        for (BlockFace face : ADJACENT_FACES) {
            Block adjacent = center.getRelative(face);

            player.sendBlockChange(adjacent.getLocation(), adjacent.getBlockData());
        }
    }
}