package com.angryguyy.metadatastripper.network;

import com.angryguyy.metadatastripper.MetadataStripper;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.world.level.block.entity.BlockEntityType;
import org.bukkit.entity.Player;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * High-performance evaluator for block entity network payloads.
 * <p>
 * Defeats Stash Finder, City ESP, and Auto Sign modules by dropping NBT data packets
 * for containers and signs that are outside of a legitimate 8-block interaction range.
 * Integrates a thread-safe violation profiler to detect exploit usage patterns asynchronously.
 */
public final class BlockEntityFilter {

    private static final double MAX_DISTANCE_SQ = 64.0;

    private static final Map<UUID, AtomicInteger> PROFILES = new ConcurrentHashMap<>();
    private static final Map<UUID, Position> POSITIONS = new ConcurrentHashMap<>();

    private BlockEntityFilter() {
        throw new UnsupportedOperationException("Utility class cannot be instantiated.");
    }

    /**
     * Evaluates whether the Block Entity data packet should be dropped based on spatial constraints.
     * Intercepts NBT payloads directly on the Netty pipeline, shielding the client from receiving
     * sensitive data of out-of-reach block entities.
     *
     * @param player the recipient player
     * @param packet the outbound NBT data packet
     * @return true if the packet should be intercepted and destroyed, false otherwise
     */
    public static boolean shouldBlock(Player player, ClientboundBlockEntityDataPacket packet) {
        return shouldBlock(player.getUniqueId(), packet.getType(), packet.getPos());
    }

    /**
     * Evaluates a block entity packet using the last region-thread position snapshot.
     *
     * @param playerUuid recipient player identifier
     * @param type block entity type carried by the packet
     * @param pos block entity position
     * @return true when the packet must be discarded
     */
    public static boolean shouldBlock(UUID playerUuid, BlockEntityType<?> type, BlockPos pos) {
        if (!isSensitive(type)) {
            return false;
        }

        Position position = POSITIONS.get(playerUuid);
        if (position == null) {
            return false;
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
     * Publishes a player position for lock-free packet filtering.
     *
     * @param player player whose position is read on its owning region thread
     */
    public static void updatePosition(Player player) {
        net.minecraft.server.level.ServerPlayer handle = ((org.bukkit.craftbukkit.entity.CraftPlayer) player).getHandle();
        POSITIONS.put(player.getUniqueId(), new Position(handle.getX(), handle.getY(), handle.getZ()));
    }

    /**
     * Retrieves and atomically resets the violation count for the specified player.
     * Designed to be polled routinely by a global scheduling task without causing thread blocking.
     *
     * @param uuid the player's unique identifier
     * @return the number of blocked NBT packets since the last check
     */
    public static int getAndResetViolations(UUID uuid) {
        AtomicInteger profile = PROFILES.get(uuid);
        return profile != null ? profile.getAndSet(0) : 0;
    }

    /**
     * Safely clears the tracking profile from memory when a player disconnects to prevent memory leaks.
     *
     * @param uuid the player's unique identifier
     */
    public static void removeProfile(UUID uuid) {
        PROFILES.remove(uuid);
        POSITIONS.remove(uuid);
    }

    /**
     * Cross-references the provided block entity type against a predefined list of sensitive containers.
     * Utilizes direct memory address comparison for nanosecond-level evaluation.
     *
     * @param type the internal NMS BlockEntityType
     * @return true if the block entity holds sensitive NBT data
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
                type == BlockEntityType.DECORATED_POT;
    }

    private record Position(double x, double y, double z) {
    }
}