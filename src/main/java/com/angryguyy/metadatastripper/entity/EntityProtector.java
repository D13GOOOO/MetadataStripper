package com.angryguyy.metadatastripper.entity;

import net.minecraft.network.protocol.game.ClientboundAddEntityPacket;
import net.minecraft.world.entity.EntityType;

public final class EntityProtector {

    public static boolean isSensitiveEntity(ClientboundAddEntityPacket packet) {
        EntityType<?> type = packet.getType();
        return type == EntityType.CHEST_MINECART || type == EntityType.HOPPER_MINECART || type == EntityType.TNT_MINECART;
    }
}