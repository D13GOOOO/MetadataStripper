package com.angryguyy.metadatastripper.tasks;

import com.angryguyy.metadatastripper.MetadataStripper;
import org.bukkit.Bukkit;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.Set;

/**
 * Ultra-lightweight Entity Culling Radar.
 * <p>
 * Replaces the heavy Netty interception for moving entities. Runs routinely
 * on the main thread, utilizing native NMS spatial hashing and native line-of-sight
 * raycasting to defeat Player ESP and Mob/Storage ESP without degrading TPS.
 */
public final class EntityVisibilityTask extends BukkitRunnable {

    private final MetadataStripper plugin;

    private static final double TRACKING_RADIUS = 48.0;

    public EntityVisibilityTask(MetadataStripper plugin) {
        this.plugin = plugin;
    }

    @Override
    public void run() {
        Set<String> sensitiveNames = plugin.getSensitiveEntities();

        for (Player player : Bukkit.getOnlinePlayers()) {
            if (plugin.getIgnoredWorlds().contains(player.getWorld().getName())) {
                continue;
            }

            for (Entity entity : player.getNearbyEntities(TRACKING_RADIUS, TRACKING_RADIUS, TRACKING_RADIUS)) {
                if (entity.equals(player)) {
                    continue; // Ignora se stesso
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
                }
            }
        }
    }
}