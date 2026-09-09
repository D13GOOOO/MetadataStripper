package com.angryguyy.metadatastripper.engine;

import com.angryguyy.metadatastripper.util.RegionSchedulerAdapter;
import com.angryguyy.metadatastripper.network.BlockEntityFilter;
import org.bukkit.Bukkit;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.craftbukkit.entity.CraftEntity;
import org.bukkit.craftbukkit.entity.CraftPlayer;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import com.mojang.datafixers.util.Pair;
import net.minecraft.network.protocol.game.ClientboundSetEquipmentPacket;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;

/**
 * High-performance spatial entity culling engine designed for Folia and Paper.
 * <p>
 * This engine reduces client-side entity-ESP exposure by suppressing metadata and equipment
 * packets for entities that are outside a strict tactical radius.
 * <p>
 * <b>Architectural Notes:</b>
 * <ul>
 *   <li><b>Execution Flow:</b> Operates across three distinct thread boundaries:
 *       <ol>
 *           <li><b>Global Scheduler:</b> Dispatches culling tasks for all online players every 5 ticks.</li>
 *           <li><b>Region Schedulers:</b> Computes spatial intersections safely on the player's owning thread.</li>
 *           <li><b>Netty I/O Threads:</b> Performs concurrent, lock-free lookups during outbound packet serialization.</li>
 *       </ol>
 *   </li>
 *   <li><b>Thread Safety & Memory Barrier:</b> Relies on a {@link ConcurrentHashMap} containing immutable,
 *       sorted primitive arrays ({@code int[]}). Once published to the map, the array is never mutated,
 *       guaranteeing thread-safe reads for the Netty pipeline without locking overhead.</li>
 *   <li><b>Algorithmic Complexity:</b>
 *       <ul>
 *           <li><i>Write path (Region Thread):</i> O(E log E) per player snapshot, where E is the number of nearby entities (due to primitive sorting).</li>
 *           <li><i>Read path (Netty Thread):</i> O(log E) binary search, ensuring zero-GC pressure and microsecond latency during packet interception.</li>
 *       </ul>
 *   </li>
 * </ul>
 */
public final class EntityCullingEngine {

    /**
     * The maximum radius (in blocks) at which an entity's metadata or equipment is sent to the client.
     * <p>
     * Dynamically configurable to balance performance and visual culling distance.
     */
    private static volatile double tacticalRadius = 32.0;

    /**
     * Lock-free map holding the latest sorted array of visible entity IDs for each online player.
     */
    private static final ConcurrentHashMap<UUID, int[]> VISIBILITY_MAP = new ConcurrentHashMap<>();

    private static volatile boolean isRunning = false;
    private static volatile Runnable schedulerCancellation = () -> { };

    /**
     * Private constructor to prevent instantiation of this utility class.
     *
     * @throws UnsupportedOperationException if called via reflection.
     */
    private EntityCullingEngine() {
        throw new UnsupportedOperationException("Utility class cannot be instantiated.");
    }

    /**
     * Updates the tactical culling radius dynamically from the plugin configuration.
     *
     * @param radius the new radius in blocks
     */
    public static void setTacticalRadius(double radius) {
        tacticalRadius = radius;
    }

    /**
     * Initializes the global asynchronous dispatcher for entity culling.
     * <p>
     * Starts a repeating global task that iterates through all online players and delegates
     * their spatial computation to their respective Region Schedulers.
     *
     * @param plugin the main plugin instance used for task scheduling
     */
    public static void initializeCullingTask(Plugin plugin) {
        if (isRunning) return;
        isRunning = true;

        schedulerCancellation = RegionSchedulerAdapter.scheduleGlobalRepeating(plugin, () -> {
            if (!isRunning) {
                return;
            }
            for (Player player : Bukkit.getOnlinePlayers()) {
                dispatchPlayerCulling(plugin, player);
            }
        }, 1L, 5L);
    }

    /**
     * Shuts down the culling engine, cancels active dispatch tasks, and purges visibility caches.
     * Called during plugin disable or reload sequences.
     */
    public static void shutdown() {
        isRunning = false;
        schedulerCancellation.run();
        schedulerCancellation = () -> { };
        VISIBILITY_MAP.clear();
    }

    /**
     * Dispatches the spatial culling logic to the correct region thread for a specific player.
     * This bridges the gap between the Global Scheduler and Folia's threaded region model.
     *
     * @param plugin the main plugin instance
     * @param player the player whose region thread should execute the spatial scan
     */
    private static void dispatchPlayerCulling(Plugin plugin, Player player) {
        RegionSchedulerAdapter.executeForEntity(plugin, player, () -> processSpatialData(player));
    }

    /**
     * Core region-thread processor. Computes spatial data, queries nearby entities natively,
     * and constructs a sorted primitive array for lock-free binary searching by the Netty thread.
     * <p>
     * If this is not the first snapshot for the player, it dynamically delegates to
     * {@link #resynchronizeNewlyVisibleEntities(Player, Map, int[], int[])} to restore state
     * for entities that have just entered the tactical radius.
     *
     * @param player the player to compute spatial data for
     */
    private static void processSpatialData(Player player) {
        if (!player.isOnline()) {
            VISIBILITY_MAP.remove(player.getUniqueId());
            return;
        }

        BlockEntityFilter.updatePosition(player);

        List<Entity> nearbyEntities = player.getNearbyEntities(tacticalRadius, tacticalRadius, tacticalRadius);
        int[] visibleIds = new int[nearbyEntities.size()];

        for (int i = 0; i < nearbyEntities.size(); i++) {
            visibleIds[i] = nearbyEntities.get(i).getEntityId();
        }

        Arrays.sort(visibleIds);

        int[] previousIds = VISIBILITY_MAP.put(player.getUniqueId(), visibleIds);

        if (previousIds != null) {
            Map<Integer, Entity> entitiesById = new HashMap<>(nearbyEntities.size());
            for (Entity entity : nearbyEntities) {
                entitiesById.put(entity.getEntityId(), entity);
            }
            resynchronizeNewlyVisibleEntities(player, entitiesById, visibleIds, previousIds);
        }
    }

    /**
     * Resynchronizes entities that have recently crossed the tactical radius threshold.
     * <p>
     * Because the Netty handler previously blocked metadata and equipment packets for these entities,
     * the client currently sees them as uninitialized (or visually naked). This method uses internal
     * NMS routines to force-send the missing data directly to the client connection.
     *
     * @param player       the player receiving the synchronized packets
     * @param entitiesById a map of currently visible entities mapped by their network ID
     * @param visibleIds   the new sorted array of visible entity IDs
     * @param previousIds  the previous sorted array of visible entity IDs
     */
    private static void resynchronizeNewlyVisibleEntities(Player player, Map<Integer, Entity> entitiesById,
                                                          int[] visibleIds, int[] previousIds) {
        net.minecraft.server.level.ServerPlayer target = ((CraftPlayer) player).getHandle();

        for (int entityId : visibleIds) {
            if (Arrays.binarySearch(previousIds, entityId) >= 0) {
                continue;
            }

            Entity entity = entitiesById.get(entityId);
            if (entity != null) {
                net.minecraft.world.entity.Entity nmsEntity = ((CraftEntity) entity).getHandle();

                nmsEntity.refreshEntityData(target);

                if (nmsEntity instanceof LivingEntity livingEntity) {
                    List<Pair<EquipmentSlot, net.minecraft.world.item.ItemStack>> equipment = new ArrayList<>();
                    for (EquipmentSlot slot : EquipmentSlot.values()) {
                        equipment.add(Pair.of(slot, livingEntity.getItemBySlot(slot).copy()));
                    }
                    target.connection.send(new ClientboundSetEquipmentPacket(nmsEntity.getId(), equipment, true));
                }
            }
        }
    }

    /**
     * Evaluates if a specific entity is within the tactical visual radius of the player.
     * <p>
     * Engineered exclusively for the Netty outbound handler. It performs a lightning-fast
     * O(log N) binary search on a primitive array, causing zero garbage collection and virtually
     * no latency on the network thread.
     * <p>
     * <i>Note: If the player does not have a computed snapshot yet, this defaults to {@code false},
     * which the calling packet filter typically treats as a passthrough to prevent incomplete initialization.</i>
     *
     * @param playerUuid the unique identifier of the querying player
     * @param entityId   the network ID of the target entity
     * @return {@code true} if the entity is tracked as visible in the latest snapshot; {@code false} otherwise
     */
    public static boolean isEntityVisible(UUID playerUuid, int entityId) {
        int[] visibleIds = VISIBILITY_MAP.get(playerUuid);
        if (visibleIds == null) {
            return false;
        }
        if (visibleIds.length == 0) {
            return false;
        }
        return Arrays.binarySearch(visibleIds, entityId) >= 0;
    }

    /**
     * Purges a player from the spatial cache to prevent memory leaks upon disconnection.
     * Must be called during the PlayerQuitEvent.
     *
     * @param playerUuid the unique identifier of the disconnected player
     */
    public static void removePlayer(UUID playerUuid) {
        VISIBILITY_MAP.remove(playerUuid);
    }

    /**
     * Safely performs garbage collection by sweeping orphaned UUID profiles.
     * <p>
     * Designed to be called by a low-priority global scheduler task to ensure
     * disconnected players or fake NPC entities do not cause memory leaks
     * if they bypass the standard PlayerQuitEvent.
     *
     * @param activeUuids a set of currently online and valid player UUIDs
     */
    public static void cleanOrphans(Set<UUID> activeUuids) {
        VISIBILITY_MAP.keySet().removeIf(uuid -> !activeUuids.contains(uuid));
    }
}