package com.angryguyy.metadatastripper.entity;

import net.minecraft.network.protocol.game.ClientboundAddEntityPacket;
import net.minecraft.world.entity.EntityType;

/**
 * Utility class for intercepting and identifying sensitive entities (e.g., storage minecarts)
 * at the native network packet level.
 * <p>
 * This acts as the frontline defense against Entity-based ESP hacks (like ChestESP or TNT finders).
 * By operating directly on the native NMS {@link ClientboundAddEntityPacket} and utilizing
 * fast reference equality checks against static {@link EntityType} constants, this class
 * blocks unauthorized entity tracking with absolute zero impact on server TPS or Garbage Collection.
 */
public final class EntityProtector {

    /**
     * Private constructor to prevent instantiation of this utility class.
     */
    private EntityProtector() {
        throw new UnsupportedOperationException("Utility class cannot be instantiated");
    }

    /**
     * Determines if the entity being spawned is considered "sensitive" and should be intercepted
     * or hidden from the client.
     *
     * @param packet the native NMS packet containing the entity spawn data.
     * @return true if the entity is a sensitive minecart (Chest, Hopper, or TNT), false otherwise.
     */
    public static boolean isSensitiveEntity(ClientboundAddEntityPacket packet) {
        /*
         * Direct memory reference comparison (O(1)). Extremely fast and GC-free,
         * perfectly suited for high-frequency Netty pipeline execution.
         */
        EntityType<?> type = packet.getType();
        return type == EntityType.CHEST_MINECART
                || type == EntityType.HOPPER_MINECART
                || type == EntityType.TNT_MINECART;
    }
}