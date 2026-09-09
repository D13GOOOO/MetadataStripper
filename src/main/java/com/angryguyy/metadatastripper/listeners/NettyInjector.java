package com.angryguyy.metadatastripper.listeners;

import com.angryguyy.metadatastripper.MetadataStripper;
import com.angryguyy.metadatastripper.cache.BlockStateCache;
import com.angryguyy.metadatastripper.network.BlockEntityFilter;
import com.angryguyy.metadatastripper.util.ObfuscationPalette;
import com.angryguyy.metadatastripper.util.ReflectionAccess;
import com.angryguyy.metadatastripper.util.RegionSchedulerAdapter;

import io.netty.channel.Channel;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.game.*;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;

import org.bukkit.World.Environment;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import sun.misc.Unsafe;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Core network interceptor for applying packet-level obfuscation.
 * <p>
 * This class injects a custom duplex handler into the Netty pipeline of each connected player.
 * It intercepts and mutates outbound chunk data and block updates before serialization, effectively
 * nullifying X-Ray mods, Cave ESPs, and Sus Chunk Finders.
 * <p>
 * <b>Architectural Notes:</b>
 * <ul>
 *   <li><b>Dual-Path Execution:</b>
 *       <ul>
 *           <li><i>Fast Path:</i> Simple entity filtering happens synchronously on the Netty I/O thread.</li>
 *           <li><i>Slow Path (Regional):</i> Complex chunk cloning is deferred to the recipient player's Folia Region
 *               Scheduler via a chained {@link CompletableFuture} queue. This ensures strict thread-safety for NMS
 *               chunk reads without blocking the network thread.</li>
 *       </ul>
 *   </li>
 *   <li><b>Backpressure Management:</b> The pipeline enforces a strict limit ({@code maxPendingRegionWrites}).
 *       If a chunk transformation exceeds 2 seconds or the queue overflows, packets are safely dropped or passed
 *       unmodified to prevent server OOM (Out-Of-Memory) crashes and client disconnections.</li>
 * </ul>
 * <p>
 * <b>Algorithmic Optimizations:</b>
 * <ul>
 *   <li><b>Zero-GC Cloning:</b> Utilizes {@code sun.misc.Unsafe} to bypass constructor allocations and garbage
 *       collection overhead when cloning massive {@link LevelChunkSection} objects.</li>
 *   <li><b>Loop Unrolling:</b> Hoists static Y-axis calculations out of the inner spatial loops, reducing
 *       per-section operations from O(N^3) complex evaluations to ultra-fast primitive assignments.</li>
 * </ul>
 */
public final class NettyInjector implements Listener {

    private static final String HANDLER_NAME = "MetadataStripper";

    private static Unsafe unsafe;
    private static Field chunkDataField;
    private static Field[] levelChunkSectionFields;
    private static Method palettedContainerCopy;
    private static Field chunkAccessSectionsField;
    private static Field blockEntitiesDataField;
    private static Field blockEntityPackedXZField;
    private static Field blockEntityYField;
    private static Field blockEntityTypeField;
    private static Throwable initializationFailure;

    static {
        try {
            Field unsafeField = Unsafe.class.getDeclaredField("theUnsafe");
            unsafeField.setAccessible(true);
            unsafe = (Unsafe) unsafeField.get(null);

            chunkDataField = resolveField(ClientboundLevelChunkWithLightPacket.class, "chunkData",
                    ClientboundLevelChunkPacketData.class);

            levelChunkSectionFields = LevelChunkSection.class.getDeclaredFields();
            for (Field f : levelChunkSectionFields) {
                f.setAccessible(true);
            }

            palettedContainerCopy = net.minecraft.world.level.chunk.PalettedContainer.class.getMethod("copy");
            palettedContainerCopy.setAccessible(true);

            chunkAccessSectionsField = resolveField(net.minecraft.world.level.chunk.ChunkAccess.class, "sections",
                    LevelChunkSection[].class);
            blockEntitiesDataField = resolveField(ClientboundLevelChunkPacketData.class, "blockEntitiesData", List.class);

            Class<?> blockEntityInfoClass = Class.forName(
                    "net.minecraft.network.protocol.game.ClientboundLevelChunkPacketData$BlockEntityInfo");
            blockEntityPackedXZField = blockEntityInfoClass.getDeclaredField("packedXZ");
            blockEntityYField = blockEntityInfoClass.getDeclaredField("y");
            blockEntityTypeField = blockEntityInfoClass.getDeclaredField("type");
            blockEntityPackedXZField.setAccessible(true);
            blockEntityYField.setAccessible(true);
            blockEntityTypeField.setAccessible(true);
        } catch (Exception exception) {
            initializationFailure = exception;
        }
    }

    private static Field resolveField(Class<?> owner, String name, Class<?> type) throws NoSuchFieldException {
        try {
            return ReflectionAccess.findField(owner, name, type);
        } catch (NoSuchFieldException exception) {
            return ReflectionAccess.findUniqueField(owner, type);
        }
    }

    private final MetadataStripper plugin;

    private volatile int aggressiveYMax = 5;
    private volatile int maxPendingRegionWrites = 32;

    /** Lock-free cache of players holding the bypass permission, avoiding slow LuckPerms lookups on the Netty thread. */
    private final Map<UUID, Boolean> bypassPlayers = new ConcurrentHashMap<>();

    /** Lock-free cache mapping player UUIDs to their NMS Entity ID to rapidly filter self-metadata packets. */
    private final Map<UUID, Integer> playerEntityIds = new ConcurrentHashMap<>();

    private final AtomicLong lastFailureLogNanos = new AtomicLong();
    private volatile boolean shuttingDown;

    /**
     * Creates a network injector bound to the plugin lifecycle and logger.
     * <p>
     * If the static reflection resolution failed, this will log the exception immediately,
     * and {@link #isReady()} will return {@code false}, pushing the engine into a {@code DEGRADED} state.
     *
     * @param plugin the owning plugin instance
     */
    public NettyInjector(MetadataStripper plugin) {
        this.plugin = plugin;
        if (initializationFailure != null) {
            plugin.getLogger().log(java.util.logging.Level.WARNING,
                    "Netty packet interception is partially unavailable", initializationFailure);
        }
    }

    /**
     * Updates advanced settings from configuration.
     *
     * @param aggressiveYMax           the Y-level threshold for aggressive Mode 2 fill
     * @param maxPendingRegionWrites   the maximum queue limit before backpressure drops occur
     */
    public void updateSettings(int aggressiveYMax, int maxPendingRegionWrites) {
        this.aggressiveYMax = aggressiveYMax;
        this.maxPendingRegionWrites = maxPendingRegionWrites;
    }

    /**
     * Reports whether all native memory and reflection handles required for chunk transformation are available.
     *
     * @return {@code true} when the chunk transformation path is fully initialized; {@code false} otherwise
     */
    public boolean isReady() {
        return chunkDataField != null && chunkAccessSectionsField != null && unsafe != null
                && levelChunkSectionFields != null && palettedContainerCopy != null
                && blockEntitiesDataField != null && blockEntityPackedXZField != null
                && blockEntityYField != null && blockEntityTypeField != null;
    }

    /**
     * Triggers the Netty channel injection immediately when a player joins the server.
     *
     * @param event the native {@link PlayerJoinEvent}
     */
    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        injectPlayer(event.getPlayer());
    }

    /**
     * Detaches the outbound handler and clears memory profiles when a player quits.
     *
     * @param event the native {@link PlayerQuitEvent}
     */
    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        removePlayer(event.getPlayer());
    }

    /**
     * Injects the custom packet interceptor directly into the player's network channel pipeline.
     * <p>
     * <b>Thread Mechanics:</b> The injected {@link ChannelDuplexHandler} acts as a gatekeeper.
     * It parses all outbound traffic and dynamically routes complex chunk operations to a serialized
     * asynchronous queue (via {@link CompletableFuture}), ensuring chunk packets are delivered in strict order
     * despite being processed asynchronously on Folia Region Schedulers.
     *
     * @param player the target player to inject. Marked {@code final} to ensure safe capture by the anonymous inner class.
     */
    public void injectPlayer(final Player player) {
        if (shuttingDown) {
            return;
        }
        try {
            UUID playerUuid = player.getUniqueId();

            bypassPlayers.put(playerUuid, player.hasPermission("metadatastripper.bypass"));
            playerEntityIds.put(playerUuid, player.getEntityId());

            Channel channel = ((CraftPlayer) player).getHandle().connection.connection.channel;
            if (channel.pipeline().get(HANDLER_NAME) != null) {
                channel.pipeline().remove(HANDLER_NAME);
            }

            channel.pipeline().addBefore("packet_handler", HANDLER_NAME, new ChannelDuplexHandler() {

                private CompletableFuture<Void> pendingRegionWrites = CompletableFuture.completedFuture(null);
                private int pendingRegionWriteCount;

                @Override
                public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
                    if (shuttingDown) {
                        promise.setSuccess();
                        return;
                    }

                    if (isRegionPacket(msg) && !Boolean.TRUE.equals(bypassPlayers.get(playerUuid))) {
                        if (pendingRegionWriteCount >= maxPendingRegionWrites) {
                            MetadataStripper.recordBackpressureDrop();
                            promise.setSuccess();
                            return;
                        }

                        CompletableFuture<Void> previous = pendingRegionWrites;
                        CompletableFuture<Void> current = new CompletableFuture<>();
                        pendingRegionWriteCount++;
                        pendingRegionWrites = current;

                        previous.whenComplete((ignored, failure) -> ctx.executor().execute(() ->
                                writeRegionPacketAsync(ctx, player, msg, promise, current,
                                        () -> pendingRegionWriteCount--)));
                        return;
                    }

                    if (!pendingRegionWrites.isDone()) {
                        pendingRegionWrites.whenComplete((ignored, failure) -> ctx.executor().execute(() ->
                                writeNonRegionPacket(ctx, playerUuid, msg, promise)));
                        return;
                    }

                    writeNonRegionPacket(ctx, playerUuid, msg, promise);
                }

                private void writeNonRegionPacket(ChannelHandlerContext ctx, UUID playerUuid,
                                                  Object msg, ChannelPromise promise) {
                    try {
                        msg = handlePacket(playerUuid, msg);
                        if (msg == null) {
                            promise.setSuccess();
                            return;
                        }
                    } catch (Exception e) {
                        plugin.getLogger().log(java.util.logging.Level.WARNING, "Error on outbound packet", e);
                    }
                    ctx.write(msg, promise);
                }
            });
        } catch (Exception exception) {
            plugin.getLogger().log(java.util.logging.Level.WARNING,
                    "Unable to inject MetadataStripper into player channel", exception);
        }
    }

    /**
     * Evaluates outbound packets that do not require complex regional NMS access.
     * <p>
     * <b>Performance:</b> Operates directly on the Netty thread. All evaluations here must be O(1)
     * and strictly lock-free. Defers to {@link BlockEntityFilter} and {@link com.angryguyy.metadatastripper.network.EntityDataFilter}.
     *
     * @param playerUuid the UUID of the recipient player
     * @param msg        the native packet instance
     * @return the original packet, or {@code null} if the packet violates proximity rules and should be destroyed
     */
    @SuppressWarnings({"unused", "deprecation"})
    private Object handlePacket(UUID playerUuid, Object msg) {
        if (Boolean.TRUE.equals(bypassPlayers.get(playerUuid))) {
            return msg;
        }

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
     * Identifies packets that must interact with the world geometry.
     *
     * @param msg the generic packet object
     * @return {@code true} if the packet contains chunk or block state data
     */
    private boolean isRegionPacket(Object msg) {
        return msg instanceof ClientboundBlockUpdatePacket || msg instanceof ClientboundLevelChunkWithLightPacket;
    }

    /**
     * Dispatches a heavy packet to the Folia Region Scheduler for asynchronous processing.
     * <p>
     * Enforces a strict 2-second bounding timeout to prevent deadlocks. If the region thread is overloaded,
     * the packet is safely dropped or passed untouched, triggering a {@code DEGRADED} diagnostic state.
     *
     * @param ctx            the Netty channel context
     * @param player         the target player
     * @param originalPacket the raw outbound packet
     * @param promise        the Netty promise to fulfill
     * @param completion     the completable future controlling the ordered queue
     * @param releaseSlot    the callback to decrement the backpressure queue count
     */
    private void writeRegionPacketAsync(ChannelHandlerContext ctx, Player player,
                                        Object originalPacket, ChannelPromise promise,
                                        CompletableFuture<Void> completion, Runnable releaseSlot) {
        AtomicBoolean completed = new AtomicBoolean();

        CompletableFuture<Object> transformed = RegionSchedulerAdapter.callForEntityAsync(
                plugin, player, () -> handleRegionPacket(player, originalPacket), 2L, TimeUnit.SECONDS);

        transformed.whenComplete((packet, throwable) -> ctx.executor().execute(() -> {
            if (!completed.compareAndSet(false, true)) {
                return;
            }
            releaseSlot.run();

            if (shuttingDown) {
                promise.setSuccess();
                completion.complete(null);
                return;
            }

            if (throwable != null) {
                boolean timeout = throwable instanceof java.util.concurrent.TimeoutException
                        || throwable.getCause() instanceof java.util.concurrent.TimeoutException;
                MetadataStripper.recordFallback(timeout);

                long now = System.nanoTime();
                long previous = lastFailureLogNanos.get();
                if (now - previous >= TimeUnit.SECONDS.toNanos(10)
                        && lastFailureLogNanos.compareAndSet(previous, now)) {
                    plugin.getLogger().log(java.util.logging.Level.WARNING,
                            timeout ? "Timed out transforming outbound packet; dropping it safely"
                                    : "Failed transforming outbound packet; dropping it safely", throwable);
                }

                promise.setSuccess();
                completion.complete(null);
                return;
            }

            if (packet == null) {
                promise.setSuccess();
                completion.complete(null);
                return;
            }

            if (originalPacket instanceof ClientboundLevelChunkWithLightPacket) {
                MetadataStripper.recordTransformedChunk();
            }

            ctx.write(packet, promise);
            completion.complete(null);
        }));
    }

    /**
     * Core region-thread mutation engine for Chunk and Block Update packets.
     * <p>
     * <b>Chunk Cloning Mechanics:</b> Because NMS Chunks are cached and shared across multiple players,
     * mutating them directly causes server-wide corruption. This method uses {@code sun.misc.Unsafe} to
     * shallow-copy the chunk section structure (Zero-GC instantiation), deep-copies the {@code PalettedContainer}
     * to safely modify blocks, and reconstructs an ephemeral packet solely for this client.
     *
     * @param player the recipient player
     * @param msg    the un-obfuscated region packet
     * @return the deeply obfuscated packet ready for client consumption, or {@code null} if it should be skipped
     */
    private Object handleRegionPacket(Player player, Object msg) {
        BlockEntityFilter.updatePosition(player);

        if (msg instanceof ClientboundBlockUpdatePacket packet) {
            Level level = ((CraftPlayer) player).getHandle().level();
            BlockState original = packet.getBlockState();
            BlockState sanitized = BlockStateCache.sanitize(original);

            if (original.getBlock() == Blocks.BEDROCK && packet.getPos().getY() > player.getWorld().getMinHeight()) {
                sanitized = ObfuscationPalette.getObfuscatedBlock(plugin.getEngineMode(), packet.getPos(), getEnvironmentFast(level));
            }
            if (original != sanitized) {
                return new ClientboundBlockUpdatePacket(packet.getPos(), sanitized);
            }
        }

        if (msg instanceof ClientboundLevelChunkWithLightPacket chunkPacket) {
            if (chunkDataField == null || chunkAccessSectionsField == null || unsafe == null) {
                MetadataStripper.recordFallback(false);
                return null;
            }

            net.minecraft.world.entity.player.Player nmsPlayer = ((CraftPlayer) player).getHandle();
            ServerLevel serverLevel = (ServerLevel) nmsPlayer.level();
            LevelChunk chunk = serverLevel.getChunkSource().getChunkNow(chunkPacket.getX(), chunkPacket.getZ());

            if (chunk == null) {
                MetadataStripper.recordFallback(false);
                return null;
            }

            Environment env = getEnvironmentFast(serverLevel);
            int engineMode = plugin.getEngineMode();
            boolean isAggressiveMode = engineMode >= 2;

            BlockState stone = ObfuscationPalette.getObfuscatedBlock(engineMode, new BlockPos(0, 1, 0), env);
            BlockState deepslate = ObfuscationPalette.getObfuscatedBlock(engineMode, new BlockPos(0, -1, 0), env);
            int minBuildHeight = player.getWorld().getMinHeight();

            boolean[] sensitiveStates = MetadataStripper.sensitiveGlobal;

            try {
                LevelChunkSection[] originalSections = chunk.getSections();
                LevelChunkSection[] clonedSections = new LevelChunkSection[originalSections.length];
                boolean chunkModified = false;

                for (int i = 0; i < originalSections.length; i++) {
                    LevelChunkSection section = originalSections[i];
                    if (section == null || section.hasOnlyAir()) {
                        clonedSections[i] = section;
                        continue;
                    }

                    int sectionY = (minBuildHeight >> 4) + i;
                    int globalSectionY = sectionY << 4;
                    boolean isUnderground = globalSectionY < aggressiveYMax;

                    boolean sectionModified = false;

                    if (isUnderground && isAggressiveMode) {
                        sectionModified = true;
                    } else {
                        for (int y = 0; y < 16; y++) {
                            int actualY = globalSectionY + y;
                            boolean checkLiquid = actualY < 55;

                            for (int x = 0; x < 16; x++) {
                                for (int z = 0; z < 16; z++) {
                                    BlockState state = section.getBlockState(x, y, z);
                                    int id = Block.getId(state);

                                    boolean isSensitive = id >= 0 && id < sensitiveStates.length && sensitiveStates[id];
                                    boolean isUndergroundLiquid = checkLiquid && !state.getFluidState().isEmpty();

                                    if (isSensitive || isUndergroundLiquid) {
                                        sectionModified = true;
                                    } else if (state.getBlock() == Blocks.BEDROCK && actualY > minBuildHeight) {
                                        sectionModified = true;
                                    }
                                }
                            }
                        }
                    }

                    if (!sectionModified) {
                        clonedSections[i] = section;
                        continue;
                    }

                    LevelChunkSection fakeSection = (LevelChunkSection) unsafe.allocateInstance(LevelChunkSection.class);
                    for (Field f : levelChunkSectionFields) {
                        if (Modifier.isStatic(f.getModifiers())) continue;
                        Object val = f.get(section);

                        if (val != null && val.getClass() == net.minecraft.world.level.chunk.PalettedContainer.class) {
                            val = palettedContainerCopy.invoke(val);
                        }
                        f.set(fakeSection, val);
                    }

                    if (isUnderground && isAggressiveMode) {
                        for (int y = 0; y < 16; y++) {
                            for (int x = 0; x < 16; x++) {
                                for (int z = 0; z < 16; z++) {
                                    BlockState originalState = section.getBlockState(x, y, z);
                                    fakeSection.setBlockState(x, y, z,
                                            originalState.getBlock() == Blocks.BEDROCK ? originalState : deepslate);
                                }
                            }
                        }
                    } else {
                        for (int y = 0; y < 16; y++) {
                            int actualY = globalSectionY + y;
                            BlockState replacement = actualY < 0 ? deepslate : stone;
                            boolean checkLiquid = actualY < 55;

                            for (int x = 0; x < 16; x++) {
                                for (int z = 0; z < 16; z++) {
                                    BlockState state = section.getBlockState(x, y, z);
                                    int id = Block.getId(state);
                                    boolean isSensitive = id >= 0 && id < sensitiveStates.length && sensitiveStates[id];
                                    boolean isUndergroundLiquid = checkLiquid && !state.getFluidState().isEmpty();

                                    if (isSensitive || isUndergroundLiquid ||
                                            (state.getBlock() == Blocks.BEDROCK && actualY > minBuildHeight)) {
                                        fakeSection.setBlockState(x, y, z, replacement);
                                    }
                                }
                            }
                        }
                    }

                    clonedSections[i] = fakeSection;
                    chunkModified = true;
                }

                if (chunkModified) {
                    LevelChunk fakeChunk = (LevelChunk) unsafe.allocateInstance(LevelChunk.class);
                    Class<?> clazz = LevelChunk.class;

                    while (clazz != null && clazz != Object.class) {
                        for (Field f : clazz.getDeclaredFields()) {
                            if (Modifier.isStatic(f.getModifiers())) continue;
                            f.setAccessible(true);
                            f.set(fakeChunk, f.get(chunk));
                        }
                        clazz = clazz.getSuperclass();
                    }

                    chunkAccessSectionsField.set(fakeChunk, clonedSections);

                    ClientboundLevelChunkPacketData fakeData = new ClientboundLevelChunkPacketData(fakeChunk);
                    filterChunkBlockEntities(fakeData, chunkPacket.getX(), chunkPacket.getZ(), player.getUniqueId());
                    chunkDataField.set(chunkPacket, fakeData);
                } else {
                    filterChunkBlockEntities(chunkPacket.getChunkData(), chunkPacket.getX(), chunkPacket.getZ(), player.getUniqueId());
                }

            } catch (Exception exception) {
                MetadataStripper.recordFallback(false);
                plugin.getLogger().log(java.util.logging.Level.WARNING,
                        "Unable to sanitize chunk packet for " + player.getName(), exception);
                return null;
            }

            return chunkPacket;
        }

        return msg;
    }

    /**
     * Scrubs sensitive Block Entities (like hidden chests or spawners) that are embedded directly inside the chunk payload.
     * <p>
     * Uses reflection to strip out the underlying NBT data, preventing Stash Finders from detecting
     * containers when a chunk is initially loaded.
     *
     * @param chunkData  the NMS chunk serialization payload
     * @param chunkX     the grid X coordinate of the chunk
     * @param chunkZ     the grid Z coordinate of the chunk
     * @param playerUuid the receiving player's UUID
     * @throws IllegalAccessException if reflection access is restricted
     */
    private void filterChunkBlockEntities(ClientboundLevelChunkPacketData chunkData, int chunkX, int chunkZ, UUID playerUuid)
            throws IllegalAccessException {
        if (blockEntitiesDataField == null || blockEntityPackedXZField == null || blockEntityYField == null || blockEntityTypeField == null) {
            return;
        }

        Object value = blockEntitiesDataField.get(chunkData);
        if (value instanceof List<?> blockEntities) {
            Iterator<?> iterator = blockEntities.iterator();
            while (iterator.hasNext()) {
                Object blockEntity = iterator.next();
                int packedXZ = blockEntityPackedXZField.getInt(blockEntity);
                int y = blockEntityYField.getInt(blockEntity);

                BlockPos position = new BlockPos((chunkX << 4) + (packedXZ & 15), y, (chunkZ << 4) + ((packedXZ >> 4) & 15));

                if (BlockEntityFilter.shouldBlock(playerUuid, (net.minecraft.world.level.block.entity.BlockEntityType<?>) blockEntityTypeField.get(blockEntity), position)) {
                    iterator.remove();
                }
            }
        }

        Iterator<net.minecraft.network.protocol.Packet<?>> extraPackets = chunkData.getExtraPackets().iterator();
        while (extraPackets.hasNext()) {
            net.minecraft.network.protocol.Packet<?> extraPacket = extraPackets.next();
            if (extraPacket instanceof ClientboundBlockEntityDataPacket blockEntityPacket
                    && BlockEntityFilter.shouldBlock(playerUuid, blockEntityPacket.getType(), blockEntityPacket.getPos())) {
                extraPackets.remove();
            }
        }
    }

    /**
     * Determines the environment dimension rapidly without expensive Bukkit API calls.
     *
     * @param level the native NMS Level instance
     * @return the corresponding Bukkit Environment
     */
    private Environment getEnvironmentFast(Level level) {
        if (level.dimension() == Level.NETHER) {
            return Environment.NETHER;
        }
        if (level.dimension() == Level.END) {
            return Environment.THE_END;
        }
        return Environment.NORMAL;
    }

    /**
     * Safely detaches the custom packet interceptor upon player disconnection.
     * <p>
     * Cleans up lock-free profiles to prevent memory leaks, and executes the pipeline removal
     * directly on the Netty Event Loop to ensure thread safety.
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
                } catch (Exception exception) {
                    plugin.getLogger().log(java.util.logging.Level.WARNING,
                            "Unable to remove MetadataStripper channel handler", exception);
                }
            });
        } catch (Exception exception) {
            plugin.getLogger().log(java.util.logging.Level.WARNING,
                    "Unable to schedule MetadataStripper channel removal", exception);
        }
    }

    /**
     * Stops accepting new packet work and clears all injector-owned memory caches.
     * Invoked during plugin shutdown or reload.
     */
    public void shutdown() {
        shuttingDown = true;
        bypassPlayers.clear();
        playerEntityIds.clear();
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
    public void cleanOrphans(Set<UUID> activeUuids) {
        bypassPlayers.keySet().removeIf(uuid -> !activeUuids.contains(uuid));
        playerEntityIds.keySet().removeIf(uuid -> !activeUuids.contains(uuid));
    }
}