package com.angryguyy.metadatastripper.listeners;

import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;

/**
 * A Zero-GC, high-performance listener responsible for dynamically revealing obfuscated blocks.
 * <p>
 * When a player breaks a block, this class immediately forces a block state update for the
 * six adjacent faces. Since single-block updates deliberately bypass the aggressive chunk
 * obfuscation pipeline in Netty, this seamlessly reveals hidden ores to legitimate players
 * during manual excavation without compromising the anti-xray integrity.
 * <p>
 * <b>Algorithmic Complexity:</b>
 * <ul>
 *   <li><b>Memory Allocation:</b> Zero-GC footprint. Relies on a pre-computed static array.</li>
 *   <li><b>Execution Time:</b> O(1) constant time execution per block break event.</li>
 * </ul>
 */
public final class BlockUpdateListener implements Listener {

    /**
     * Pre-allocated static array containing the six cardinal block faces.
     * Prevents object instantiation overhead during the highly frequent block-break event cycle.
     */
    private static final BlockFace[] ADJACENT_FACES = {
            BlockFace.UP, BlockFace.DOWN, BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST
    };

    /**
     * Intercepts block break events at the MONITOR priority level to ensure the event
     * was not cancelled by other protection plugins (e.g., WorldGuard, Towny) before acting.
     *
     * @param event the native block break event
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