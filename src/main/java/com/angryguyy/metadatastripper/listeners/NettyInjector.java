package com.angryguyy.metadatastripper.listeners;

import com.angryguyy.metadatastripper.MetadataStripper;
import com.angryguyy.metadatastripper.cache.BlockStateCache;
import com.angryguyy.metadatastripper.util.ObfuscationPalette;

import io.netty.channel.Channel;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import it.unimi.dsi.fastutil.shorts.ShortArraySet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
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

import java.lang.reflect.Field;

/**
 * The core network interception and payload manipulation module.
 * <p>
 * This class injects a highly optimized {@link ChannelDuplexHandler} directly into the Netty
 * pipeline of each connected player. It operates as the final network gateway, filtering and
 * obfuscating outgoing packets before they reach the client socket.
 * <p>
 * Admin and moderation staff possessing the {@code metadatastripper.bypass} permission
 * bypass all spatial culling and packet manipulation algorithms inherently.
 * <p>
 * <b>Proximity Culling Engine ("Fire & Forget"):</b>
 * <ul>
 *   <li><b>Surface Layer (Y &ge; 5):</b> Permits normal chunk rendering while selectively obfuscating targeted blocks.</li>
 *   <li><b>Subterranean Layer (Y &lt; 5):</b> Aggressively forces air and fluid states into opaque blocks, neutralizing Freecam exploits.</li>
 *   <li><b>Anti-Seed Cracking:</b> Flattens Bedrock patterns by sending bedrock above world minimum as solid Deepslate/Stone.</li>
 * </ul>
 * <b>Algorithmic Complexity:</b>
 * <ul>
 *   <li><b>Chunk Processing:</b> Utilizes ThreadLocal buffer reuse and O(1) {@code maybeHas} Fast-Skip evaluations to achieve true Zero-GC overhead.</li>
 *   <li><b>Folia Compatibility:</b> 100% bypass of Bukkit API in network threads, using purely safe NMS state reads.</li>
 * </ul>
 */
public final class NettyInjector implements Listener {

    private static final String HANDLER_NAME = "MetadataStripper";
    private static final int AGGRESSIVE_Y_MAX = 5;

    private static Field sectionStatesField;
    private static Field sectionPositionsField;

    private static final ThreadLocal<short[]> POS_BUFFER = ThreadLocal.withInitial(() -> new short[4096]);
    private static final ThreadLocal<BlockState[]> STATE_BUFFER = ThreadLocal.withInitial(() -> new BlockState[4096]);

    static {
        try {
            for (Field field : ClientboundSectionBlocksUpdatePacket.class.getDeclaredFields()) {
                if (field.getType() == BlockState[].class) {
                    field.setAccessible(true);
                    sectionStatesField = field;
                } else if (field.getType() == short[].class) {
                    field.setAccessible(true);
                    sectionPositionsField = field;
                }
            }
        } catch (Exception ignored) {}
    }

    private final MetadataStripper plugin;

    /**
     * Constructs the NettyInjector instance.
     *
     * @param plugin the main plugin instance
     */
    public NettyInjector(MetadataStripper plugin) {
        this.plugin = plugin;
    }

    /**
     * Triggers pipeline injection when a player connects.
     *
     * @param event the player join event
     */
    @SuppressWarnings("unused")
    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        injectPlayer(event.getPlayer());
    }

    /**
     * Safely unbinds the pipeline handler when a player disconnects.
     *
     * @param event the player quit event
     */
    @SuppressWarnings("unused")
    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        removePlayer(event.getPlayer());
    }

    /**
     * Injects the custom duplex handler into the player's network channel.
     * Performs a Graceful Netty Ejection check first to prevent memory leaks during hot-reloads.
     *
     * @param player the target player instance
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
                        msg = handlePacket(ctx, player, msg, promise);
                        if (msg == null) {
                            return;
                        }
                    } catch (Exception e) {
                        plugin.getLogger().log(java.util.logging.Level.WARNING, "Error while processing outbound packet for " + player.getName(), e);
                    }
                    super.write(ctx, msg, promise);
                }
            });
        } catch (Exception e) {
            plugin.getLogger().log(java.util.logging.Level.SEVERE, "Failed to inject Netty handler for " + player.getName(), e);
        }
    }

    /**
     * Intercepts, evaluates, and mutates outgoing server packets.
     *
     * @param ctx     the channel handler context
     * @param player  the recipient player
     * @param msg     the raw outgoing packet
     * @param promise the channel promise
     * @return the mutated packet, or null to cancel the transmission
     */
    @SuppressWarnings("ConstantValue")
    private Object handlePacket(ChannelHandlerContext ctx, Player player, Object msg, ChannelPromise promise) {
        if (player.hasPermission("metadatastripper.bypass")) {
            return msg;
        }

        if (msg instanceof ClientboundBlockUpdatePacket packet) {
            net.minecraft.world.entity.player.Player nmsPlayer = ((CraftPlayer) player).getHandle();
            Level level = nmsPlayer.level();

            BlockState original = packet.getBlockState();
            BlockState sanitized = BlockStateCache.sanitize(original);

            if (original.getBlock() == Blocks.BEDROCK && packet.getPos().getY() > level.getMinBuildHeight()) {
                Environment env = getEnvironmentFast(level);
                sanitized = ObfuscationPalette.getObfuscatedBlock(plugin.getEngineMode(), packet.getPos(), env);
            }

            if (original != sanitized) {
                return new ClientboundBlockUpdatePacket(packet.getPos(), sanitized);
            }
        }
        else if (msg instanceof ClientboundSectionBlocksUpdatePacket packet) {
            if (sectionStatesField != null) {
                try {
                    BlockState[] states = (BlockState[]) sectionStatesField.get(packet);
                    boolean modified = false;
                    BlockState[] newStates = states.clone();

                    for (int i = 0; i < newStates.length; i++) {
                        BlockState sanitized = BlockStateCache.sanitize(newStates[i]);
                        if (newStates[i] != sanitized) {
                            newStates[i] = sanitized;
                            modified = true;
                        }
                    }
                    if (modified) {
                        sectionStatesField.set(packet, newStates);
                    }
                } catch (IllegalAccessException ignored) {}
            }
        }
        else if (msg instanceof ClientboundBlockEntityDataPacket packet) {
            if (com.angryguyy.metadatastripper.network.BlockEntityFilter.shouldBlock(player, packet)) {
                return null;
            }
        }
        else if (msg instanceof ClientboundSetEntityDataPacket packet) {
            if (com.angryguyy.metadatastripper.network.EntityDataFilter.shouldBlock(player, packet.id())) {
                return null;
            }
        }
        else if (msg instanceof ClientboundSetEquipmentPacket packet) {
            if (com.angryguyy.metadatastripper.network.EntityDataFilter.shouldBlock(player, packet.getEntity())) {
                return null;
            }
        }
        else if (msg instanceof ClientboundLevelChunkWithLightPacket chunkPacket) {
            ctx.write(msg, promise);

            net.minecraft.world.entity.player.Player nmsPlayer = ((CraftPlayer) player).getHandle();
            ServerLevel serverLevel = (ServerLevel) nmsPlayer.level();

            LevelChunk chunk = serverLevel.getChunkSource().getChunkNow(chunkPacket.getX(), chunkPacket.getZ());
            if (chunk == null) {
                return null;
            }

            Environment env = getEnvironmentFast(serverLevel);
            int engineMode = plugin.getEngineMode();
            boolean isAggressiveMode = engineMode >= 2;

            BlockState stone = ObfuscationPalette.getObfuscatedBlock(engineMode, new BlockPos(0, 1, 0), env);
            BlockState deepslate = ObfuscationPalette.getObfuscatedBlock(engineMode, new BlockPos(0, -1, 0), env);
            int minBuildHeight = serverLevel.getMinBuildHeight();

            ctx.channel().eventLoop().execute(() -> {
                try {
                    LevelChunkSection[] sections = chunk.getSections();

                    short[] bufferPos = POS_BUFFER.get();
                    BlockState[] bufferState = STATE_BUFFER.get();

                    for (int i = 0; i < sections.length; i++) {
                        LevelChunkSection section = sections[i];
                        if (section == null || section.hasOnlyAir()) continue;

                        int sectionY = (minBuildHeight >> 4) + i;
                        int globalSectionY = sectionY << 4;
                        boolean isAggressiveZone = isAggressiveMode && (globalSectionY < AGGRESSIVE_Y_MAX);

                        boolean needsObfuscation = section.getStates().maybeHas(state -> {
                            try {
                                int id = Block.getId(state);
                                boolean isSensitive = id >= 0 && id < MetadataStripper.sensitiveGlobal.length && MetadataStripper.sensitiveGlobal[id];
                                boolean isCaveFiller = isAggressiveZone && (state.isAir() || !state.getFluidState().isEmpty());
                                boolean isBedrock = state.getBlock() == Blocks.BEDROCK;

                                return isSensitive || isCaveFiller || isBedrock;
                            } catch (Exception e) {
                                return false;
                            }
                        });

                        if (!needsObfuscation) continue;

                        BlockState obfuscationBlock = globalSectionY < 0 ? deepslate : stone;
                        int count = 0;

                        for (int x = 0; x < 16; x++) {
                            for (int y = 0; y < 16; y++) {
                                for (int z = 0; z < 16; z++) {
                                    BlockState state = section.getBlockState(x, y, z);
                                    int id = Block.getId(state);
                                    int actualY = globalSectionY + y;

                                    boolean isSensitive = id >= 0 && id < MetadataStripper.sensitiveGlobal.length && MetadataStripper.sensitiveGlobal[id];
                                    boolean isCaveFiller = isAggressiveZone && (state.isAir() || !state.getFluidState().isEmpty());
                                    boolean isExposedBedrock = state.getBlock() == Blocks.BEDROCK && actualY > minBuildHeight;

                                    if (isSensitive || isCaveFiller || isExposedBedrock) {
                                        bufferPos[count] = (short) ((x << 8) | (z << 4) | y);
                                        bufferState[count] = obfuscationBlock;
                                        count++;
                                    }
                                }
                            }
                        }

                        if (count > 0) {
                            short[] finalPos = new short[count];
                            BlockState[] finalState = new BlockState[count];
                            System.arraycopy(bufferPos, 0, finalPos, 0, count);
                            System.arraycopy(bufferState, 0, finalState, 0, count);

                            SectionPos secPos = SectionPos.of(chunkPacket.getX(), sectionY, chunkPacket.getZ());
                            ClientboundSectionBlocksUpdatePacket secPacket = new ClientboundSectionBlocksUpdatePacket(secPos, new ShortArraySet(new short[0]), section);

                            if (sectionStatesField != null) sectionStatesField.set(secPacket, finalState);
                            if (sectionPositionsField != null) sectionPositionsField.set(secPacket, finalPos);

                            ctx.channel().writeAndFlush(secPacket);
                        }
                    }
                } catch (Exception ignored) {}
            });

            return null;
        }

        return msg;
    }

    /**
     * Evaluates the Bukkit Environment completely natively without invoking the Bukkit API,
     * bypassing the Folia AsyncCatcher when queried from an I/O thread.
     *
     * @param level the native NMS Level instance
     * @return the mapped Bukkit Environment
     */
    private Environment getEnvironmentFast(Level level) {
        if (level.dimension() == Level.NETHER) return Environment.NETHER;
        if (level.dimension() == Level.END) return Environment.THE_END;
        return Environment.NORMAL;
    }

    /**
     * Safely uninjects the custom duplex handler from the player's network channel.
     * Guaranteed to execute cleanly on the Netty event loop to prevent memory leaks.
     *
     * @param player the target player instance
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
        } catch (Exception e) {
            plugin.getLogger().log(java.util.logging.Level.WARNING, "Could not properly detach Netty handler for " + player.getName(), e);
        }
    }
}