package com.angryguyy.metadatastripper.entity;

import com.angryguyy.metadatastripper.MetadataStripper;
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket;
import net.minecraft.world.entity.EntityType;
import org.bukkit.plugin.java.JavaPlugin;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

/**
 * Utility class for intercepting and identifying sensitive entities.
 * <p>
 * This acts as the frontline defense against Entity-based ESP hacks (like Mob ESP or ChestESP).
 * It dynamically links the plugin's configuration to native NMS EntityTypes via reflection,
 * caching them in a high-performance Identity Set to ensure O(1) zero-GC lookups during packet interception.
 */
public final class EntityProtector {

    private static Set<EntityType<?>> sensitiveTypes;

    private EntityProtector() {
        throw new UnsupportedOperationException("Utility class cannot be instantiated");
    }

    /**
     * Determines if the entity being spawned is considered sensitive and should be intercepted.
     *
     * @param packet the native NMS packet containing the entity spawn data
     * @return true if the entity is sensitive, false otherwise
     */
    public static boolean isSensitiveEntity(ClientboundAddEntityPacket packet) {
        if (sensitiveTypes == null) {
            initialize();
        }
        return sensitiveTypes.contains(packet.getType());
    }

    private static synchronized void initialize() {
        if (sensitiveTypes != null) {
            return;
        }

        Set<EntityType<?>> types = Collections.newSetFromMap(new IdentityHashMap<>());

        try {
            MetadataStripper plugin = JavaPlugin.getPlugin(MetadataStripper.class);
            Set<String> configList = plugin.getSensitiveEntities();

            for (Field field : EntityType.class.getFields()) {
                if (Modifier.isStatic(field.getModifiers()) && field.getType() == EntityType.class) {
                    String fieldName = field.getName();
                    String normalized = fieldName;

                    if (normalized.equals("CHEST_MINECART")) normalized = "MINECART_CHEST";
                    else if (normalized.equals("HOPPER_MINECART")) normalized = "MINECART_HOPPER";
                    else if (normalized.equals("TNT_MINECART")) normalized = "MINECART_TNT";

                    if (configList.contains(normalized) || configList.contains(fieldName)) {
                        types.add((EntityType<?>) field.get(null));
                    }
                }
            }
        } catch (Exception ignored) {}

        if (types.isEmpty()) {
            types.add(EntityType.CHEST_MINECART);
            types.add(EntityType.HOPPER_MINECART);
            types.add(EntityType.TNT_MINECART);
            types.add(EntityType.ITEM_FRAME);
            types.add(EntityType.GLOW_ITEM_FRAME);
            types.add(EntityType.ARMOR_STAND);
            types.add(EntityType.VILLAGER);
            types.add(EntityType.ZOMBIE_VILLAGER);
            types.add(EntityType.IRON_GOLEM);
            types.add(EntityType.COW);
            types.add(EntityType.SHEEP);
            types.add(EntityType.PIG);
            types.add(EntityType.HORSE);
        }

        sensitiveTypes = types;
    }
}