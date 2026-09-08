package com.angryguyy.metadatastripper.listeners;

import com.angryguyy.metadatastripper.MetadataStripper;
import com.angryguyy.metadatastripper.cache.BlockStateCache;
import com.angryguyy.metadatastripper.util.ObfuscationPalette;

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

/**
 * Core network interceptor for applying hardware-level packet obfuscation.
 * <p>
 * This class injects a custom duplex handler into the Netty pipeline of each connected player.
 * It strictly intercepts and mutates chunk data and block updates before serialization, effectively
 * nullifying X-Ray mods, Cave ESPs, and Sus Chunk Finders without inducing main-thread latency.
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
        } catch (Exception ignored) {}
    }

    private final MetadataStripper plugin;

    public NettyInjector(MetadataStripper plugin) {
        this.plugin = plugin;
    }

    @SuppressWarnings("unused")
    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        injectPlayer(event.getPlayer());
    }

    @SuppressWarnings("unused")
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
            Channel channel = ((CraftPlayer) player).getHandle().connection.connection.channel;
            if (channel.pipeline().get(HANDLER_NAME) != null) {
                channel.pipeline().remove(HANDLER_NAME);
            }

            channel.pipeline().addBefore("packet_handler", HANDLER_NAME, new ChannelDuplexHandler() {
                @Override
                public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
                    try {
                        msg = handlePacket(player, msg);
                        if (msg == null) {
                            return;
                        }
                    } catch (Exception e) {
                        plugin.getLogger().log(java.util.logging.Level.WARNING, "Error on outbound packet", e);
                    }
                    super.write(ctx, msg, promise);
                }
            });
        } catch (Exception ignored) {}
    }

    /**
     * Core packet evaluator and mutator. Evaluates outbound payloads synchronously.
     *
     * @param player the packet recipient
     * @param msg the native packet instance
     * @return the mutated packet, or null if the packet should be dropped
     */
    @SuppressWarnings({"unused", "deprecation"})
    private Object handlePacket(Player player, Object msg) {
        if (player.hasPermission("metadatastripper.bypass")) {
            return msg;
        }

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
        else if (msg instanceof ClientboundBlockEntityDataPacket packet && com.angryguyy.metadatastripper.network.BlockEntityFilter.shouldBlock(player, packet)) {
            return null;
        }
        else if (msg instanceof ClientboundSetEntityDataPacket packet && com.angryguyy.metadatastripper.network.EntityDataFilter.shouldBlock(player, packet.id())) {
            return null;
        }
        else if (msg instanceof ClientboundSetEquipmentPacket packet && com.angryguyy.metadatastripper.network.EntityDataFilter.shouldBlock(player, packet.getEntity())) {
            return null;
        }
        else if (msg instanceof ClientboundLevelChunkWithLightPacket chunkPacket) {
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

                    LevelChunkSection fakeSection = (LevelChunkSection) unsafe.allocateInstance(LevelChunkSection.class);
                    for (Field f : levelChunkSectionFields) {
                        if (Modifier.isStatic(f.getModifiers())) continue;
                        Object val = f.get(section);

                        if (val != null && val.getClass() == net.minecraft.world.level.chunk.PalettedContainer.class) {
                            val = palettedContainerCopy.invoke(val);
                        }
                        f.set(fakeSection, val);
                    }

                    boolean sectionModified = false;

                    if (isUnderground && isAggressiveMode) {
                        for (int y = 0; y < 16; y++) {
                            for (int x = 0; x < 16; x++) {
                                for (int z = 0; z < 16; z++) {
                                    BlockState originalState = section.getBlockState(x, y, z);
                                    if (originalState.getBlock() == Blocks.BEDROCK) {
                                        fakeSection.setBlockState(x, y, z, originalState);
                                    } else {
                                        fakeSection.setBlockState(x, y, z, deepslate);
                                    }
                                }
                            }
                        }
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

                                    boolean isSensitive = id >= 0 && id < MetadataStripper.sensitiveGlobal.length && MetadataStripper.sensitiveGlobal[id];
                                    boolean isUndergroundLiquid = checkLiquid && !state.getFluidState().isEmpty();

                                    if (isSensitive || isUndergroundLiquid) {
                                        fakeSection.setBlockState(x, y, z, replacement);
                                        sectionModified = true;
                                    } else if (state.getBlock() == Blocks.BEDROCK && actualY > minBuildHeight) {
                                        fakeSection.setBlockState(x, y, z, replacement);
                                        sectionModified = true;
                                    }
                                }
                            }
                        }
                    }

                    clonedSections[i] = sectionModified ? fakeSection : section;
                    if (sectionModified) {
                        chunkModified = true;
                    }
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
                    chunkDataField.set(chunkPacket, fakeData);
                }

            } catch (Exception ignored) {}

            return chunkPacket;
        }

        return msg;
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
        try {
            Channel channel = ((CraftPlayer) player).getHandle().connection.connection.channel;
            channel.eventLoop().execute(() -> {
                try {
                    if (channel.pipeline().get(HANDLER_NAME) != null) {
                        channel.pipeline().remove(HANDLER_NAME);
                    }
                } catch (Exception ignored) {}
            });
        } catch (Exception ignored) {}
    }
}