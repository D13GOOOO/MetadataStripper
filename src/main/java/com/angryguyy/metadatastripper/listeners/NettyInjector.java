package com.angryguyy.metadatastripper.listeners;

import com.angryguyy.metadatastripper.MetadataStripper;
import com.angryguyy.metadatastripper.network.BlockEntityFilter;
import com.angryguyy.metadatastripper.network.ChunkPacketTransformer;
import com.angryguyy.metadatastripper.util.RegionSchedulerAdapter;

import io.netty.channel.Channel;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;

import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.network.protocol.game.ClientboundSectionBlocksUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket;
import net.minecraft.network.protocol.game.ClientboundSetEquipmentPacket;
import net.minecraft.server.network.ServerGamePacketListenerImpl;

import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerLoginEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Core network interceptor and pipeline manager.
 * <p>
 * Responsible for injecting duplex handlers into player network channels, managing
 * the strict backpressure packet queue, and efficiently routing packets to either the
 * fast-path entity filters or the slow-path regional chunk transformers.
 * <p>
 * <b>Architectural Notes:</b>
 * <ul>
 *   <li><b>Relog Exploit Mitigation:</b> Injects the network handler during the earliest
 *       {@link PlayerLoginEvent} phase to secure the channel before initial world chunks are sent.</li>
 *   <li><b>Packet Ordering:</b> A chained {@link CompletableFuture} system guarantees that despite
 *       chunks being processed asynchronously on Folia region threads, they are written back to the
 *       client in their exact original sequence, preventing client-side desynchronization or KeepAlive timeouts.</li>
 * </ul>
 */
public final class NettyInjector implements Listener {

    /** The unique identifier for the injected pipeline handler. */
    private static final String HANDLER_NAME = "MetadataStripper";

    private final MetadataStripper plugin;

    /** The Y-level threshold below which aggressive mode 2 solid fill is applied. */
    private volatile int aggressiveYMax = 5;

    /**
     * Increased queue limit to accommodate massive chunk sends during fast Freecam flight or teleports.
     * Prevents X-Ray leaks on outer chunks.
     */
    private volatile int maxPendingRegionWrites = 2048;

    /** Lock-free cache of players holding the bypass permission, avoiding slow permission lookups on the Netty thread. */
    private final Map<UUID, Boolean> bypassPlayers = new ConcurrentHashMap<>();

    /** Lock-free cache mapping player UUIDs to their native NMS Entity ID for rapid self-metadata filtering. */
    private final Map<UUID, Integer> playerEntityIds = new ConcurrentHashMap<>();

    /** Volatile flag indicating the plugin is shutting down, preventing new packet queues from forming. */
    private volatile boolean shuttingDown;

    /**
     * Instantiates the network injector and validates the underlying NMS reflection transformer health.
     *
     * @param plugin the owning plugin instance
     */
    public NettyInjector(MetadataStripper plugin) {
        this.plugin = plugin;
        if (ChunkPacketTransformer.initializationFailure != null) {
            plugin.getLogger().log(java.util.logging.Level.WARNING,
                    "Netty packet interception is partially unavailable due to NMS reflection failures",
                    ChunkPacketTransformer.initializationFailure);
        }
    }

    /**
     * Dynamically updates the pipeline configuration limits during a hot-reload.
     *
     * @param aggressiveYMax         the Y-level threshold for subterranean solid fill obfuscation
     * @param maxPendingRegionWrites the maximum capacity of the asynchronous packet queue
     */
    public void updateSettings(int aggressiveYMax, int maxPendingRegionWrites) {
        this.aggressiveYMax = aggressiveYMax;
        this.maxPendingRegionWrites = maxPendingRegionWrites;
    }

    /**
     * Verifies if the underlying NMS chunk transformer successfully initialized its reflection handles.
     *
     * @return {@code true} if the pipeline is safe to process chunk packets; {@code false} otherwise
     */
    public boolean isReady() {
        return ChunkPacketTransformer.isReady();
    }

    /**
     * Intercepts the player pipeline at the earliest possible stage (Login).
     * This explicitly mitigates the "Relog Exploit" by securing the channel
     * BEFORE the server dispatches the initial world chunks.
     *
     * @param event the native {@link PlayerLoginEvent}
     */
    @SuppressWarnings("unused")
    @EventHandler(priority = EventPriority.LOWEST)
    public void onPlayerLogin(PlayerLoginEvent event) {
        if (event.getResult() == PlayerLoginEvent.Result.ALLOWED) {
            injectPlayer(event.getPlayer());
        }
    }

    /**
     * Fallback injection during the standard join phase.
     * Ensures compatibility if authentication plugins delayed the pipeline creation.
     *
     * @param event the native {@link PlayerJoinEvent}
     */
    @SuppressWarnings("unused")
    @EventHandler(priority = EventPriority.LOWEST)
    public void onPlayerJoin(PlayerJoinEvent event) {
        injectPlayer(event.getPlayer());
    }

    /**
     * Triggers pipeline cleanup and memory profile removal when a player disconnects.
     *
     * @param event the native {@link PlayerQuitEvent}
     */
    @SuppressWarnings("unused")
    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        removePlayer(event.getPlayer());
    }

    /**
     * Injects the custom duplex handler directly into the player's underlying Netty channel pipeline.
     * <p>
     * Implements a strict asynchronous queueing model. Packets are evaluated natively on the I/O thread.
     * Heavy structural packets (Chunks) are dispatched to the Folia/Paper region scheduler and re-queued
     * for writing upon completion.
     *
     * @param player the target player to inject
     */
    @SuppressWarnings("resource")
    public void injectPlayer(final Player player) {
        if (shuttingDown) return;

        try {
            net.minecraft.server.level.ServerPlayer nmsPlayer = ((CraftPlayer) player).getHandle();
            ServerGamePacketListenerImpl packetListener = nmsPlayer.connection;

            // Safety guard: The connection might not be fully bound yet depending on the exact login phase.
            if (packetListener == null || packetListener.connection == null || packetListener.connection.channel == null) {
                return;
            }

            Channel channel = packetListener.connection.channel;

            // Avoid duplicate handlers if both Login and Join events attempt injection
            if (channel.pipeline().get(HANDLER_NAME) != null) {
                return;
            }

            UUID playerUuid = player.getUniqueId();
            bypassPlayers.put(playerUuid, player.hasPermission("metadatastripper.bypass"));
            playerEntityIds.put(playerUuid, player.getEntityId());

            channel.pipeline().addBefore("packet_handler", HANDLER_NAME, new ChannelDuplexHandler() {

                private CompletableFuture<Void> pendingRegionWrites = CompletableFuture.completedFuture(null);
                private int pendingRegionWriteCount;

                @Override
                public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) {
                    if (shuttingDown) {
                        promise.setSuccess();
                        return;
                    }

                    boolean bypass = Boolean.TRUE.equals(bypassPlayers.get(playerUuid));

                    if (isRegionPacket(msg) && !bypass) {
                        if (pendingRegionWriteCount >= maxPendingRegionWrites) {
                            MetadataStripper.recordBackpressureDrop();
                            // SECURITY FIX: Never send raw/unobfuscated chunks when queue is full.
                            // Drop the packet entirely to maintain strict Anti-Xray integrity.
                            promise.setSuccess();
                            return;
                        }

                        pendingRegionWriteCount++;
                        CompletableFuture<Void> previous = pendingRegionWrites;
                        CompletableFuture<Void> current = new CompletableFuture<>();
                        pendingRegionWrites = current;

                        CompletableFuture<Object> transformed = RegionSchedulerAdapter.callForEntityAsync(
                                plugin, player, () -> ChunkPacketTransformer.transformRegionPacket(plugin, player, msg, aggressiveYMax), 2L, TimeUnit.SECONDS);

                        previous.whenComplete((ignored, prevFailure) -> {
                            transformed.whenComplete((packet, throwable) -> {
                                ctx.executor().execute(() -> {
                                    pendingRegionWriteCount--;

                                    if (shuttingDown) {
                                        promise.setSuccess();
                                        current.complete(null);
                                        return;
                                    }

                                    if (throwable != null) {
                                        boolean timeout = throwable instanceof java.util.concurrent.TimeoutException
                                                || throwable.getCause() instanceof java.util.concurrent.TimeoutException;
                                        MetadataStripper.recordFallback(timeout);
                                        promise.setSuccess();
                                    } else if (packet != null) {
                                        if (msg instanceof ClientboundLevelChunkWithLightPacket) {
                                            MetadataStripper.recordTransformedChunk();
                                        }
                                        ctx.write(packet, promise);
                                    } else {
                                        promise.setSuccess();
                                    }

                                    current.complete(null);
                                });
                            });
                        });
                        return;
                    }

                    if (!pendingRegionWrites.isDone()) {
                        CompletableFuture<Void> previous = pendingRegionWrites;
                        CompletableFuture<Void> current = new CompletableFuture<>();
                        pendingRegionWrites = current;

                        previous.whenComplete((ignored, failure) -> ctx.executor().execute(() -> {
                            if (shuttingDown) {
                                promise.setSuccess();
                            } else {
                                writeNonRegionPacket(ctx, playerUuid, msg, promise);
                            }
                            current.complete(null);
                        }));
                        return;
                    }

                    writeNonRegionPacket(ctx, playerUuid, msg, promise);
                }

                private void writeNonRegionPacket(ChannelHandlerContext ctx, UUID playerUuid, Object msg, ChannelPromise promise) {
                    try {
                        msg = handlePacket(playerUuid, msg);
                        if (msg == null) {
                            promise.setSuccess();
                            return;
                        }
                    } catch (Exception e) {
                        plugin.getLogger().log(java.util.logging.Level.WARNING, "Error on outbound fast-path packet", e);
                    }
                    ctx.write(msg, promise);
                }
            });
        } catch (Exception exception) {
            plugin.getLogger().log(java.util.logging.Level.WARNING, "Unable to inject MetadataStripper into player channel", exception);
        }
    }

    /**
     * Fast-path packet evaluation operating directly on the Netty I/O thread.
     * <p>
     * Handles O(1) filtering for block entities, entity metadata, and equipment payloads.
     * Guaranteed to never block the network pipeline.
     *
     * @param playerUuid the target player UUID
     * @param msg        the outbound network packet
     * @return the original packet, or {@code null} if the packet is dropped by the security filters
     */
    private Object handlePacket(UUID playerUuid, Object msg) {
        if (Boolean.TRUE.equals(bypassPlayers.get(playerUuid))) return msg;

        if (msg instanceof ClientboundBlockEntityDataPacket packet && BlockEntityFilter.shouldBlock(playerUuid, packet.getType(), packet.getPos())) {
            return null;
        }
        if (msg instanceof ClientboundSetEntityDataPacket packet && com.angryguyy.metadatastripper.network.EntityDataFilter.shouldBlock(playerUuid, playerEntityIds.getOrDefault(playerUuid, -1), packet.id())) {
            return null;
        }
        if (msg instanceof ClientboundSetEquipmentPacket packet && com.angryguyy.metadatastripper.network.EntityDataFilter.shouldBlock(playerUuid, playerEntityIds.getOrDefault(playerUuid, -1), packet.getEntity())) {
            return null;
        }
        return msg;
    }

    /**
     * Identifies complex packets containing spatial geometry that require safe NMS interaction
     * on the designated Region Scheduler.
     *
     * @param msg the outbound network packet
     * @return {@code true} if the packet is a chunk, block update, or section blocks update payload
     */
    private boolean isRegionPacket(Object msg) {
        return msg instanceof ClientboundBlockUpdatePacket
                || msg instanceof ClientboundLevelChunkWithLightPacket
                || msg instanceof ClientboundSectionBlocksUpdatePacket;
    }

    /**
     * Safely uninjects the custom pipeline handler and purges in-memory player profiles.
     * <p>
     * Enqueues the pipeline removal specifically on the Netty Event Loop to ensure thread safety
     * and prevent concurrent modification exceptions during disconnection.
     *
     * @param player the disconnecting player
     */
    public void removePlayer(Player player) {
        UUID playerUuid = player.getUniqueId();
        bypassPlayers.remove(playerUuid);
        playerEntityIds.remove(playerUuid);
        try {
            Channel channel = ((CraftPlayer) player).getHandle().connection.connection.channel;
            channel.eventLoop().execute(() -> {
                try {
                    if (channel.pipeline().get(HANDLER_NAME) != null) {
                        channel.pipeline().remove(HANDLER_NAME);
                    }
                } catch (Exception e) {
                    plugin.getLogger().log(java.util.logging.Level.WARNING, "Unable to remove MetadataStripper handler", e);
                }
            });
        } catch (Exception e) {
            plugin.getLogger().log(java.util.logging.Level.WARNING, "Unable to schedule handler removal", e);
        }
    }

    /**
     * Halts all pipeline processing and clears all lock-free memory caches.
     * Invoked exclusively during server shutdown or plugin hot-reloads.
     */
    public void shutdown() {
        shuttingDown = true;
        bypassPlayers.clear();
        playerEntityIds.clear();
    }

    /**
     * Garbage Collection task to sweep orphaned UUID profiles.
     * <p>
     * Prevents memory leaks caused by fake NPC entities or forceful disconnections
     * that might bypass standard quit events.
     *
     * @param activeUuids an authoritative set of currently online player UUIDs
     */
    public void cleanOrphans(Set<UUID> activeUuids) {
        bypassPlayers.keySet().removeIf(uuid -> !activeUuids.contains(uuid));
        playerEntityIds.keySet().removeIf(uuid -> !activeUuids.contains(uuid));
    }
}