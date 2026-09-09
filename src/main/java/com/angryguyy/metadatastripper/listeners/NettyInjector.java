package com.angryguyy.metadatastripper.listeners;

import com.angryguyy.metadatastripper.MetadataStripper;
import com.angryguyy.metadatastripper.cache.BlockStateCache;
import com.angryguyy.metadatastripper.network.BlockEntityFilter;
import com.angryguyy.metadatastripper.util.ObfuscationPalette;
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
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Core network interceptor for applying packet-level obfuscation.
 * <p>
 * This class injects a custom duplex handler into the Netty pipeline of each connected player.
 * It strictly intercepts and mutates chunk data and block updates before serialization, effectively
 * nullifying X-Ray mods, Cave ESPs, and Sus Chunk Finders while delegating NMS chunk access to
 * the recipient player's Paper or Folia scheduler.
 * <p>
 * <b>Algorithmic Optimizations:</b>
 * <ul>
 *   <li><b>Memory Allocation:</b> Utilizes {@code sun.misc.Unsafe} to bypass constructor allocations and GC overhead when cloning chunk sections.</li>
 *   <li><b>Loop Unrolling:</b> Hoists static Y-axis calculations out of the inner spatial loops, reducing per-section operations from O(N^3) complex evaluations to primitive assignments.</li>
 * </ul>
 */
public final class NettyInjector implements Listener {

    private static final String HANDLER_NAME = "MetadataStripper";
    private static final int AGGRESSIVE_Y_MAX = 5;

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

            for (Field field : ClientboundLevelChunkWithLightPacket.class.getDeclaredFields()) {
                if (field.getType() == ClientboundLevelChunkPacketData.class) {
                    field.setAccessible(true);
                    chunkDataField = field;
                    break;
                }
            }

            levelChunkSectionFields = LevelChunkSection.class.getDeclaredFields();
            for (Field f : levelChunkSectionFields) {
                f.setAccessible(true);
            }

            palettedContainerCopy = net.minecraft.world.level.chunk.PalettedContainer.class.getMethod("copy");
            palettedContainerCopy.setAccessible(true);

            for (Field f : net.minecraft.world.level.chunk.ChunkAccess.class.getDeclaredFields()) {
                if (f.getType() == LevelChunkSection[].class) {
                    f.setAccessible(true);
                    chunkAccessSectionsField = f;
                    break;
                }
            }

            for (Field f : ClientboundLevelChunkPacketData.class.getDeclaredFields()) {
                if (List.class.isAssignableFrom(f.getType())) {
                    f.setAccessible(true);
                    blockEntitiesDataField = f;
                    break;
                }
            }

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

    private final MetadataStripper plugin;
    private final Map<UUID, Boolean> bypassPlayers = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> playerEntityIds = new ConcurrentHashMap<>();

    /**
     * Creates an injector bound to the plugin lifecycle and logger.
     *
     * @param plugin owning plugin instance
     */
    public NettyInjector(MetadataStripper plugin) {
        this.plugin = plugin;
        if (initializationFailure != null) {
            plugin.getLogger().log(java.util.logging.Level.WARNING,
                    "Netty packet interception is partially unavailable", initializationFailure);
        }
    }

    /**
     * Injects the outbound handler when a player joins.
     *
     * @param event player join event
     */
    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        injectPlayer(event.getPlayer());
    }

    /**
     * Removes the outbound handler when a player quits.
     *
     * @param event player quit event
     */
    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        removePlayer(event.getPlayer());
    }

    /**
     * Injects the custom packet interceptor into the player's network channel.
     *
     * @param player the target player
     */
    public void injectPlayer(Player player) {
        try {
            UUID playerUuid = player.getUniqueId();
            bypassPlayers.put(playerUuid, player.hasPermission("metadatastripper.bypass"));
            playerEntityIds.put(playerUuid, player.getEntityId());
            Channel channel = ((CraftPlayer) player).getHandle().connection.connection.channel;
            if (channel.pipeline().get(HANDLER_NAME) != null) {
                channel.pipeline().remove(HANDLER_NAME);
            }

            channel.pipeline().addBefore("packet_handler", HANDLER_NAME, new ChannelDuplexHandler() {
                @Override
                public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
                    try {
                        msg = handlePacket(player, playerUuid, msg);
                        if (msg == null) {
                            return;
                        }
                    } catch (Exception e) {
                        plugin.getLogger().log(java.util.logging.Level.WARNING, "Error on outbound packet", e);
                    }
                    super.write(ctx, msg, promise);
                }
            });
        } catch (Exception exception) {
            plugin.getLogger().log(java.util.logging.Level.WARNING,
                "Unable to inject MetadataStripper into player channel", exception);
        }
    }

    /**
     * Core packet evaluator and mutator. Evaluates outbound payloads synchronously.
     *
     * @param player the packet recipient
     * @param msg the native packet instance
     * @return the mutated packet, or null if the packet should be dropped
     */
    @SuppressWarnings({"unused", "deprecation"})
    private Object handlePacket(Player player, UUID playerUuid, Object msg) throws InterruptedException, ExecutionException, TimeoutException {
        if (Boolean.TRUE.equals(bypassPlayers.get(playerUuid))) {
            return msg;
        }

        if (msg instanceof ClientboundBlockUpdatePacket || msg instanceof ClientboundLevelChunkWithLightPacket) {
            return RegionSchedulerAdapter.callForEntity(plugin, player, () -> handleRegionPacket(player, msg), 2L, TimeUnit.SECONDS);
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
                return msg;
            }

            net.minecraft.world.entity.player.Player nmsPlayer = ((CraftPlayer) player).getHandle();
            ServerLevel serverLevel = (ServerLevel) nmsPlayer.level();
            LevelChunk chunk = serverLevel.getChunkSource().getChunkNow(chunkPacket.getX(), chunkPacket.getZ());

            if (chunk == null) {
                return msg;
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
                    boolean isUnderground = globalSectionY < AGGRESSIVE_Y_MAX;

                    boolean sectionModified = false;

                    if (isUnderground && isAggressiveMode) {
                        sectionModified = true;
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
                plugin.getLogger().log(java.util.logging.Level.WARNING,
                        "Unable to sanitize chunk packet for " + player.getName(), exception);
            }

            return chunkPacket;
        }

        return msg;
    }

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
}