package com.angryguyy.metadatastripper.tasks;

import com.angryguyy.metadatastripper.MetadataStripper;
import org.bukkit.Bukkit;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;

import java.util.Set;

/**
 * Ultra-lightweight Entity Culling Radar with Dynamic TPS Auto-Tuning.
 * <p>
 * Replaces the heavy Netty interception for moving entities. Runs routinely
 * utilizing native spatial hashing and line-of-sight raycasting.
 * <p>
 * <b>Folia Supported:</b> Fully compliant with the Paper/Folia Global Region Scheduling API.
 * Dispatches bounding box queries and raycasts directly to the specific region thread owning each player,
 * preventing cross-thread state corruption and crashing.
 */
public final class EntityVisibilityTask implements Runnable {

    private final MetadataStripper plugin;

    /**
     * Constructs the visibility radar task.
     *
     * @param plugin the main plugin instance
     */
    public EntityVisibilityTask(MetadataStripper plugin) {
        this.plugin = plugin;
    }

    /**
     * Executes the proximity and line-of-sight evaluations for all active connections.
     * Adjusts the spatial tracking radius dynamically based on the 1-minute server TPS average.
     */
    @Override
    public void run() {
        double currentTps = Bukkit.getTPS()[0];

        final double trackingRadius;
        if (currentTps < 15.0) {
            trackingRadius = 16.0;
        } else if (currentTps < 18.5) {
            trackingRadius = 32.0;
        } else {
            trackingRadius = 48.0;
        }

        Set<String> sensitiveNames = plugin.getSensitiveEntities();

        for (Player player : Bukkit.getOnlinePlayers()) {
            if (plugin.getIgnoredWorlds().contains(player.getWorld().getName())) {
                continue;
            }

            player.getScheduler().execute(plugin, () -> {
                if (!player.isOnline()) {
                    return;
                }

                for (Entity entity : player.getNearbyEntities(trackingRadius, trackingRadius, trackingRadius)) {
                    if (entity.equals(player)) {
                        continue;
                    }

                    boolean isPlayer = entity instanceof Player;
                    boolean isSensitive = sensitiveNames.contains(entity.getType().name());

                    if (!isPlayer && !isSensitive) {
                        continue;
                    }

                    if (player.hasLineOfSight(entity)) {
                        player.showEntity(plugin, entity);
                    } else {
                        player.hideEntity(plugin, entity);
                        MetadataStripper.culledEntities.incrementAndGet();
                    }
                }
            }, null, 1L);
        }
    }
}