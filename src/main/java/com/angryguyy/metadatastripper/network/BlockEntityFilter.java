package com.angryguyy.metadatastripper.network;

import com.angryguyy.metadatastripper.MetadataStripper;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntityType;
import org.bukkit.entity.Player;

import java.util.Map;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * High-performance evaluator for block entity network payloads.
 * <p>
 * This class neutralizes advanced client-side exploit modules (such as Stash Finder, City ESP, and Auto Sign)
 * by aggressively intercepting and dropping NBT data packets for sensitive block entities (containers, signs, spawners)
 * that fall outside of a strict 8-block legitimate interaction range.
 * <p>
 * <b>Architectural Notes:</b>
 * <ul>
 *   <li><b>Execution Context:</b> Operates concurrently across multiple thread domains. Player position updates
 *       originate from the Region Schedulers, while packet evaluation ({@link #shouldBlock(UUID, BlockEntityType, BlockPos)})
 *       executes directly within the highly sensitive Netty I/O outbound event loop.</li>
 *   <li><b>Thread Safety & Memory Barrier:</b> Employs lock-free {@link ConcurrentHashMap}s and {@code volatile}
 *       data structures. This ensures that position snapshots and configuration reloads are safely published
 *       to the network threads without introducing locking contention.</li>
 *   <li><b>Performance Optimization:</b> Engineered for nanosecond-level packet evaluation. It avoids computationally
 *       expensive square roots ({@link Math#sqrt(double)}) by using squared distance thresholding, and utilizes
 *       direct reference equality ({@code ==}) for NMS identity checks.</li>
 * </ul>
 */
public final class BlockEntityFilter {

    /**
     * The squared tactical distance (8 blocks * 8 blocks = 64.0) used for spatial filtering.
     * Comparing squared distances prevents the CPU overhead of calculating square roots on every packet.
     */
    private static final double MAX_DISTANCE_SQ = 64.0;

    /**
     * Lock-free map tracking the number of blocked NBT packets per player.
     * Used asynchronously by the global violation profiler to alert staff of suspicious activity.
     */
    private static final Map<UUID, AtomicInteger> PROFILES = new ConcurrentHashMap<>();

    /**
     * Lock-free map holding the latest spatial coordinate snapshots for online players.
     */
    private static final Map<UUID, Position> POSITIONS = new ConcurrentHashMap<>();

    /**
     * Immutable, thread-safe set of dynamically configured sensitive materials.
     */
    private static volatile Set<String> configuredSensitiveMaterials = Set.of();

    /**
     * Private constructor to prevent instantiation of this utility class.
     *
     * @throws UnsupportedOperationException if called via reflection.
     */
    private BlockEntityFilter() {
        throw new UnsupportedOperationException("Utility class cannot be instantiated.");
    }

    /**
     * Core evaluation logic for block entity packets using the last known region-thread position snapshot.
     * <p>
     * <b>Security Fallback:</b> If a player's position is unknown (e.g., during the exact tick of login before
     * the region thread publishes the first snapshot), this method defaults to {@code true} (blocking the packet)
     * to ensure zero-trust compliance.
     *
     * @param playerUuid the unique identifier of the recipient player
     * @param type       the native block entity type carried by the packet
     * @param pos        the absolute 3D spatial coordinates of the block entity
     * @return {@code true} when the packet violates proximity rules and must be discarded; {@code false} otherwise
     */
    public static boolean shouldBlock(UUID playerUuid, BlockEntityType<?> type, BlockPos pos) {
        if (!isSensitive(type)) {
            return false;
        }

        Position position = POSITIONS.get(playerUuid);
        if (position == null) {
            return true;
        }

        double dx = position.x - pos.getX();
        double dy = position.y - pos.getY();
        double dz = position.z - pos.getZ();

        if ((dx * dx + dy * dy + dz * dz) > MAX_DISTANCE_SQ) {
            MetadataStripper.interceptedNbtPackets.incrementAndGet();

            AtomicInteger profile = PROFILES.get(playerUuid);

            if (profile == null) {
                AtomicInteger newProfile = new AtomicInteger(0);
                AtomicInteger existing = PROFILES.putIfAbsent(playerUuid, newProfile);
                profile = existing != null ? existing : newProfile;
            }

            profile.incrementAndGet();

            return true;
        }

        return false;
    }

    /**
     * Publishes a player's exact native coordinates to the lock-free tracking map.
     * <p>
     * <b>Execution Requirement:</b> This method must be called by the player's owning Region Thread
     * (e.g., synchronized with {@link com.angryguyy.metadatastripper.engine.EntityCullingEngine})
     * to safely read native NMS coordinates without violating Folia's threading model.
     *
     * @param player the player whose position is being snapshotted
     */
    public static void updatePosition(Player player) {
        net.minecraft.server.level.ServerPlayer handle = ((org.bukkit.craftbukkit.entity.CraftPlayer) player).getHandle();
        POSITIONS.put(player.getUniqueId(), new Position(handle.getX(), handle.getY(), handle.getZ()));
    }

    /**
     * Retrieves and atomically resets the violation count for the specified player.
     * <p>
     * Designed to be polled routinely by a global scheduling task (e.g., the staff alert profiler)
     * without causing thread blocking or impacting Netty pipeline performance.
     *
     * @param uuid the unique identifier of the player being profiled
     * @return the number of blocked NBT packets since the last check, or 0 if none
     */
    public static int getAndResetViolations(UUID uuid) {
        AtomicInteger profile = PROFILES.get(uuid);
        return profile != null ? profile.getAndSet(0) : 0;
    }

    /**
     * Safely clears tracking profiles and position snapshots from memory when a player disconnects.
     * Prevents memory leaks within the static caches.
     *
     * @param uuid the unique identifier of the disconnected player
     */
    public static void removeProfile(UUID uuid) {
        PROFILES.remove(uuid);
        POSITIONS.remove(uuid);
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
        PROFILES.keySet().removeIf(uuid -> !activeUuids.contains(uuid));
        POSITIONS.keySet().removeIf(uuid -> !activeUuids.contains(uuid));
    }

    /**
     * Purges all static player profiles and configuration data.
     * Primarily used during plugin shutdown to leave a clean memory state.
     */
    public static void clearAll() {
        PROFILES.clear();
        POSITIONS.clear();
        configuredSensitiveMaterials = Set.of();
    }

    /**
     * Publishes configured block materials used to extend block entity protection dynamically.
     * <p>
     * Converts the provided list into an immutable {@link HashSet} and publishes it to the
     * {@code volatile} field, guaranteeing safe reads across all network threads instantly.
     *
     * @param materials a set of normalized Bukkit material names loaded from the configuration
     */
    public static void updateSensitiveMaterials(Set<String> materials) {
        configuredSensitiveMaterials = Set.copyOf(new HashSet<>(materials));
    }

    /**
     * Cross-references the provided block entity type against a predefined list of sensitive containers.
     * <p>
     * <b>Performance:</b> Utilizes direct memory address comparison ({@code ==}) against internal NMS
     * constants for nanosecond-level evaluation, bypassing standard {@code equals()} overhead.
     *
     * @param type the internal NMS {@link BlockEntityType}
     * @return {@code true} if the block entity holds sensitive NBT data; {@code false} otherwise
     */
    private static boolean isSensitive(BlockEntityType<?> type) {
        return type == BlockEntityType.CHEST ||
                type == BlockEntityType.TRAPPED_CHEST ||
                type == BlockEntityType.BARREL ||
                type == BlockEntityType.SHULKER_BOX ||
                type == BlockEntityType.SIGN ||
                type == BlockEntityType.HANGING_SIGN ||
                type == BlockEntityType.MOB_SPAWNER ||
                type == BlockEntityType.VAULT ||
                type == BlockEntityType.DECORATED_POT ||
                configuredSensitiveMaterials.contains(materialName(type));
    }

    /**
     * Maps a native NMS BlockEntityType to its equivalent Bukkit material name string.
     * Allows dynamic configuration rules to intersect seamlessly with native NMS packet filtering.
     *
     * @param type the internal NMS {@link BlockEntityType}
     * @return the normalized string representation of the material, or an empty string if unmapped
     */
    private static String materialName(BlockEntityType<?> type) {
        if (type == BlockEntityType.TRIAL_SPAWNER) return "TRIAL_SPAWNER";
        if (type == BlockEntityType.CHEST) return "CHEST";
        if (type == BlockEntityType.TRAPPED_CHEST) return "TRAPPED_CHEST";
        if (type == BlockEntityType.BARREL) return "BARREL";
        if (type == BlockEntityType.SHULKER_BOX) return "SHULKER_BOX";
        if (type == BlockEntityType.MOB_SPAWNER) return "SPAWNER";
        if (type == BlockEntityType.VAULT) return "VAULT";
        if (type == BlockEntityType.DECORATED_POT) return "DECORATED_POT";
        if (type == BlockEntityType.SIGN) return "OAK_SIGN";
        if (type == BlockEntityType.HANGING_SIGN) return "OAK_HANGING_SIGN";
        return "";
    }

    /**
     * An immutable data carrier representing a player's spatial coordinates.
     *
     * @param x the exact double-precision X coordinate
     * @param y the exact double-precision Y coordinate
     * @param z the exact double-precision Z coordinate
     */
    private record Position(double x, double y, double z) {
    }
}