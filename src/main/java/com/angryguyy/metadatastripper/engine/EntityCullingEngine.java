package com.angryguyy.metadatastripper.engine;

import com.angryguyy.metadatastripper.util.RegionSchedulerAdapter;
import org.bukkit.Bukkit;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * High-performance spatial entity culling engine designed for Folia and Paper.
 * <p>
 * Manages asynchronous and regional task delegation to eliminate main-thread lag.
 * Computes visibility maps and exposes an O(log N) lock-free read path
 * for the Netty I/O threads using primitive arrays to guarantee Zero-GC footprint.
 */
public final class EntityCullingEngine {

    private static final double TACTICAL_RADIUS = 32.0;
    private static final ConcurrentHashMap<UUID, int[]> VISIBILITY_MAP = new ConcurrentHashMap<>();
    private static volatile boolean isRunning = false;

    private EntityCullingEngine() {
        throw new UnsupportedOperationException("Utility class cannot be instantiated.");
    }

    /**
     * Initializes the global asynchronous dispatcher for entity culling.
     *
     * @param plugin the main plugin instance
     */
    public static void initializeCullingTask(Plugin plugin) {
        if (isRunning) return;
        isRunning = true;

        Bukkit.getAsyncScheduler().runAtFixedRate(plugin, task -> {
            if (!isRunning) {
                task.cancel();
                return;
            }
            for (Player player : Bukkit.getOnlinePlayers()) {
                dispatchPlayerCulling(plugin, player);
            }
        }, 0L, 100L, TimeUnit.MILLISECONDS);
    }

    /**
     * Shuts down the culling engine and purges the visibility caches.
     */
    public static void shutdown() {
        isRunning = false;
        VISIBILITY_MAP.clear();
    }

    /**
     * Dispatches the spatial culling logic to the correct region thread for a specific player.
     *
     * @param plugin the main plugin instance
     * @param player the player to process
     */
    private static void dispatchPlayerCulling(Plugin plugin, Player player) {
        RegionSchedulerAdapter.executeForEntity(plugin, player, () -> processSpatialData(player));
    }

    /**
     * Processes the spatial data, querying nearby entities natively and constructing
     * a sorted primitive array for lock-free binary searching by the Netty thread.
     *
     * @param player the player to compute spatial data for
     */
    private static void processSpatialData(Player player) {
        if (!player.isOnline()) {
            VISIBILITY_MAP.remove(player.getUniqueId());
            return;
        }

        List<Entity> nearbyEntities = player.getNearbyEntities(TACTICAL_RADIUS, TACTICAL_RADIUS, TACTICAL_RADIUS);
        int[] visibleIds = new int[nearbyEntities.size()];

        for (int i = 0; i < nearbyEntities.size(); i++) {
            visibleIds[i] = nearbyEntities.get(i).getEntityId();
        }

        Arrays.sort(visibleIds);
        VISIBILITY_MAP.put(player.getUniqueId(), visibleIds);
    }

    /**
     * Evaluates if a specific entity is within the tactical visual radius of the player.
     * Engineered for instantaneous lock-free reads on the Netty I/O thread.
     *
     * @param playerUuid the unique identifier of the querying player
     * @param entityId   the network ID of the target entity
     * @return true if the entity is tracked as visible, false otherwise
     */
    public static boolean isEntityVisible(UUID playerUuid, int entityId) {
        int[] visibleIds = VISIBILITY_MAP.get(playerUuid);
        if (visibleIds == null || visibleIds.length == 0) {
            return false;
        }
        return Arrays.binarySearch(visibleIds, entityId) >= 0;
    }

    /**
     * Purges a player from the spatial cache to prevent memory leaks upon disconnection.
     *
     * @param playerUuid the unique identifier of the disconnected player
     */
    public static void removePlayer(UUID playerUuid) {
        VISIBILITY_MAP.remove(playerUuid);
    }
}