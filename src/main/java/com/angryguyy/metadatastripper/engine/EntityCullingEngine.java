package com.angryguyy.metadatastripper.engine;

import com.angryguyy.metadatastripper.util.RegionSchedulerAdapter;
import com.angryguyy.metadatastripper.network.BlockEntityFilter;
import org.bukkit.Bukkit;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * High-performance spatial entity culling engine designed for Folia and Paper.
 * <p>
 * Manages global dispatch and regional task delegation. It computes visibility snapshots
 * on the owning player region and exposes an O(log N) read path for packet filtering.
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

        RegionSchedulerAdapter.scheduleGlobalRepeating(plugin, () -> {
            if (!isRunning) {
                return;
            }
            for (Player player : Bukkit.getOnlinePlayers()) {
                dispatchPlayerCulling(plugin, player);
            }
        }, 1L, 5L);
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

        BlockEntityFilter.updatePosition(player);
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
        if (visibleIds == null) {
            return true;
        }
        if (visibleIds.length == 0) {
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