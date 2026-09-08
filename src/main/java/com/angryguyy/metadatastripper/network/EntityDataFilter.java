package com.angryguyy.metadatastripper.network;

import com.angryguyy.metadatastripper.MetadataStripper;
import net.minecraft.world.entity.Entity;
import org.bukkit.craftbukkit.CraftWorld;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.entity.Player;

/**
 * High-performance, Zero-GC evaluator for Entity Metadata payloads.
 * <p>
 * Defeats Armor Buster, Entity Owner ESP, and Pop Chams by dropping metadata and equipment
 * packets for entities that fall outside the legitimate tactical engagement radius.
 */
public final class EntityDataFilter {

    private static final double MAX_TACTICAL_DISTANCE_SQ = 1024.0;

    private EntityDataFilter() {
        throw new UnsupportedOperationException("Utility class cannot be instantiated.");
    }

    /**
     * Evaluates whether the entity metadata or equipment packet should be dropped based on spatial proximity.
     * Engineered to operate safely and concurrently on Netty I/O threads without stalling Folia Region Threads.
     *
     * @param player   the recipient player
     * @param entityId the network ID of the target entity
     * @return true if the packet should be intercepted and destroyed, false otherwise
     */
    public static boolean shouldBlock(Player player, int entityId) {
        try {
            Entity target = ((CraftWorld) player.getWorld()).getHandle().getEntity(entityId);
            Entity playerHandle = ((CraftPlayer) player).getHandle();

            if (target == null || target == playerHandle) {
                return false;
            }

            double dx = playerHandle.getX() - target.getX();
            double dy = playerHandle.getY() - target.getY();
            double dz = playerHandle.getZ() - target.getZ();

            if ((dx * dx + dy * dy + dz * dz) > MAX_TACTICAL_DISTANCE_SQ) {
                MetadataStripper.interceptedEntityPackets.incrementAndGet();
                return true;
            }

            return false;
        } catch (Exception e) {
            return true;
        }
    }
}