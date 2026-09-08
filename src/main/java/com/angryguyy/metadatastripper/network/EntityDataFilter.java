package com.angryguyy.metadatastripper.network;

import com.angryguyy.metadatastripper.MetadataStripper;
import com.angryguyy.metadatastripper.engine.EntityCullingEngine;
import org.bukkit.entity.Player;

/**
 * High-performance, Zero-GC evaluator for Entity Metadata payloads.
 * <p>
 * Defeats Armor Buster, Entity Owner ESP, and Pop Chams by dropping metadata and equipment
 * packets for entities that fall outside the legitimate tactical engagement radius.
 */
public final class EntityDataFilter {

    private EntityDataFilter() {
        throw new UnsupportedOperationException("Utility class cannot be instantiated.");
    }

    /**
     * Evaluates whether the entity metadata or equipment packet should be dropped based on spatial proximity.
     * Engineered to operate safely and concurrently on Netty I/O threads by querying a lock-free,
     * pre-computed primitive array, entirely avoiding Bukkit/Folia main-thread desynchronization.
     *
     * @param player   the recipient player
     * @param entityId the network ID of the target entity
     * @return true if the packet should be intercepted and destroyed, false otherwise
     */
    public static boolean shouldBlock(Player player, int entityId) {
        if (player.getEntityId() == entityId) {
            return false;
        }

        if (!EntityCullingEngine.isEntityVisible(player.getUniqueId(), entityId)) {
            MetadataStripper.interceptedEntityPackets.incrementAndGet();
            return true;
        }

        return false;
    }
}