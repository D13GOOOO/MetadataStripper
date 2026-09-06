package com.angryguyy.metadatastripper.listeners;

import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntitySpawnEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.event.world.ChunkUnloadEvent;

import com.angryguyy.metadatastripper.MetadataStripper;

import java.util.EnumSet;
import java.util.Set;

/**
 * Intercepts sensitive entities to prevent ESP and Chunk Finder clients from locating hidden bases.
 * <p>
 * Entities registered here are globally tracked and instantly hidden from all players
 * on spawn or chunk load, delegating visibility logic to the asynchronous ray-tracer.
 * Performance is maximized by utilizing a pre-calculated EnumSet for O(1) type lookups.
 */
public final class EntityListener implements Listener {

    private final MetadataStripper plugin;
    private final Set<EntityType> sensitiveTypes;

    /**
     * Constructs the EntityListener and initializes the O(1) EnumSet for fast lookups.
     *
     * @param plugin the main plugin instance
     */
    public EntityListener(MetadataStripper plugin) {
        this.plugin = plugin;
        this.sensitiveTypes = EnumSet.noneOf(EntityType.class);
        initializeSensitiveTypes();
    }

    private void initializeSensitiveTypes() {
        Set<String> configList = plugin.getSensitiveEntities();

        for (EntityType type : EntityType.values()) {
            String name = type.name();
            String reversed = name;

            if (name.equals("CHEST_MINECART")) reversed = "MINECART_CHEST";
            else if (name.equals("MINECART_CHEST")) reversed = "CHEST_MINECART";
            else if (name.equals("HOPPER_MINECART")) reversed = "MINECART_HOPPER";
            else if (name.equals("MINECART_HOPPER")) reversed = "HOPPER_MINECART";
            else if (name.equals("TNT_MINECART")) reversed = "MINECART_TNT";
            else if (name.equals("MINECART_TNT")) reversed = "TNT_MINECART";

            if (configList.contains(name) || configList.contains(reversed)) {
                sensitiveTypes.add(type);
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onChunkLoad(ChunkLoadEvent event) {
        if (plugin.getIgnoredWorlds().contains(event.getWorld().getName())) {
            return;
        }

        for (Entity entity : event.getChunk().getEntities()) {
            checkAndTrackEntity(entity);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntitySpawn(EntitySpawnEvent event) {
        if (plugin.getIgnoredWorlds().contains(event.getEntity().getWorld().getName())) {
            return;
        }

        checkAndTrackEntity(event.getEntity());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();

        if (plugin.getIgnoredWorlds().contains(player.getWorld().getName())) {
            return;
        }

        for (Entity entity : plugin.getGlobalSensitiveEntities().values()) {
            if (entity.getWorld().equals(player.getWorld())) {
                player.hideEntity(plugin, entity);
            }
        }
    }

    private void checkAndTrackEntity(Entity entity) {
        if (sensitiveTypes.contains(entity.getType())) {
            plugin.getGlobalSensitiveEntities().put(entity.getEntityId(), entity);

            for (Player player : entity.getWorld().getPlayers()) {
                player.hideEntity(plugin, entity);
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onChunkUnload(ChunkUnloadEvent event) {
        for (Entity entity : event.getChunk().getEntities()) {
            plugin.getGlobalSensitiveEntities().remove(entity.getEntityId());
        }
    }
}