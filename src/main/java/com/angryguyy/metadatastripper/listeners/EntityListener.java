package com.angryguyy.metadatastripper.listeners;

import org.bukkit.Bukkit;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntitySpawnEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.event.world.ChunkUnloadEvent;

import com.angryguyy.metadatastripper.MetadataStripper;

/**
 * Intercepts sensitive entities (such as Storage Minecarts and Item Frames) to prevent
 * ESP and Chunk Finder clients from locating hidden bases.
 * <p>
 * Entities registered here are globally tracked and instantly hidden from all players
 * on spawn or chunk load, delegating visibility logic to the asynchronous ray-tracer.
 */
public final class EntityListener implements Listener {

    private final MetadataStripper plugin;

    /**
     * Constructs the EntityListener.
     *
     * @param plugin the main plugin instance
     */
    public EntityListener(MetadataStripper plugin) {
        this.plugin = plugin;
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

    private void checkAndTrackEntity(Entity entity) {
        EntityType type = entity.getType();

        if (plugin.getSensitiveEntities().contains(type.name())) {
            plugin.getGlobalSensitiveEntities().put(entity.getEntityId(), entity);

            for (Player player : Bukkit.getOnlinePlayers()) {
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