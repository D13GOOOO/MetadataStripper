package com.angryguyy.metadatastripper.network;

import com.angryguyy.metadatastripper.MetadataStripper;
import com.angryguyy.metadatastripper.engine.EntityCullingEngine;

import java.util.UUID;

/**
 * High-performance evaluator for entity metadata and equipment network payloads.
 * <p>
 * This class neutralizes advanced combat-oriented client exploits—such as Armor Buster,
 * Entity Owner ESP, and Pop Chams—by aggressively intercepting and dropping metadata and
 * equipment packets for entities that fall outside the legitimate 32-block tactical engagement radius.
 * <p>
 * <b>Architectural Notes:</b>
 * <ul>
 *   <li><b>Execution Context:</b> Operates entirely on the asynchronous Netty I/O outbound threads.
 *       It strictly avoids directly querying Bukkit/Folia spatial APIs (which would throw asynchronous
 *       access exceptions or cause severe desynchronization).</li>
 *   <li><b>Performance & Memory:</b> Relies on the O(log N) lock-free binary search provided by the
 *       {@link EntityCullingEngine}. This guarantees microsecond-level packet evaluation with
 *       zero object allocation (Zero-GC pressure) during the highly frequent entity update cycle.</li>
 *   <li><b>Telemetry Integration:</b> Automatically increments global atomic counters when packets
 *       are successfully blocked, feeding the diagnostic dashboard ({@code /ms diagnose}).</li>
 * </ul>
 */
public final class EntityDataFilter {

    /**
     * Private constructor to prevent instantiation of this utility class.
     *
     * @throws UnsupportedOperationException if called via reflection.
     */
    private EntityDataFilter() {
        throw new UnsupportedOperationException("Utility class cannot be instantiated.");
    }

    /**
     * Core evaluation logic for entity visibility that operates entirely independently of the Bukkit API.
     * <p>
     * <b>Self-Data Bypass:</b> This method inherently guarantees that a player will always receive
     * metadata updates concerning their own entity (e.g., taking damage, status effects, self-equipment changes),
     * preventing client-side desynchronization and visual bugs.
     *
     * @param playerUuid     the unique identifier of the recipient player
     * @param playerEntityId the network entity identifier of the recipient player
     * @param entityId       the network entity identifier of the target entity
     * @return {@code true} when the entity packet violates the tactical radius and must be discarded; {@code false} otherwise
     * @see EntityCullingEngine#isEntityVisible(UUID, int)
     */
    public static boolean shouldBlock(UUID playerUuid, int playerEntityId, int entityId) {
        if (playerEntityId == entityId) {
            return false;
        }

        if (!EntityCullingEngine.isEntityVisible(playerUuid, entityId)) {
            MetadataStripper.interceptedEntityPackets.incrementAndGet();
            MetadataStripper.culledEntities.incrementAndGet();
            return true;
        }

        return false;
    }
}