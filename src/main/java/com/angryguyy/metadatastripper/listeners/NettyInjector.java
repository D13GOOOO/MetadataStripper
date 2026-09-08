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
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;

import org.bukkit.World.Environment;
import org.bukkit.craftbukkit.CraftWorld;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.lang.reflect.Field;
import java.util.logging.Level;

/**
 * The core network interception and payload manipulation module.
 * <p>
 * This class injects a highly optimized {@link ChannelDuplexHandler} directly into the Netty
 * pipeline of each connected player. It operates as the final network gateway, filtering and
 * obfuscating outgoing packets before they reach the client socket.
 * <p>
 * <b>Proximity Culling Engine ("Fire & Forget"):</b>
 * <ul>
 *   <li><b>Surface Layer (Y &ge; 5):</b> Permits normal chunk rendering while selectively obfuscating targeted blocks (Ores, Containers).</li>
 *   <li><b>Subterranean Layer (Y &lt; 5):</b> Aggressively forces all air and fluid states into opaque obfuscation blocks, completely neutralizing Freecam exploits.</li>
 * </ul>
 * <b>Algorithmic Complexity:</b>
 * <ul>
 *   <li><b>Chunk Processing:</b> Utilizes buffer reuse and O(1) {@code maybeHas} Fast-Skip evaluations to eliminate GC overhead during massive chunk loading.</li>
 * </ul>
 */
public final class NettyInjector implements Listener {

    private static final String HANDLER_NAME = "MetadataStripper";
    private static Field sectionStatesField;
    private static Field sectionPositionsField;

    private static final int AGGRESSIVE_Y_MAX = 5;

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

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        injectPlayer(event.getPlayer());
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        removePlayer(event.getPlayer());
    }

    /**
     * Injects the custom duplex handler into the player's network channel.
     *
     * @param player the target player instance
     */
    public void injectPlayer(Player player) {
        Channel channel = ((CraftPlayer) player).getHandle().connection.connection.channel;

        if (channel.pipeline().get(HANDLER_NAME) != null) {
            return;
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
                    plugin.getLogger().log(Level.WARNING, "Error while processing outbound packet for " + player.getName(), e);
                }

                super.write(ctx, msg, promise);
            }
        });
    }

    /**
     * intercepts, evaluates, and mutates outgoing server packets.
     *
     * @param ctx    the channel handler context
     * @param player the recipient player
     * @param msg    the raw outgoing packet
     * @param promise the channel promise
     * @return the mutated packet, or null to cancel the transmission
     */
    private Object handlePacket(ChannelHandlerContext ctx, Player player, Object msg, ChannelPromise promise) {
        if (msg instanceof ClientboundBlockUpdatePacket packet) {
            BlockState original = packet.getBlockState();
            BlockState sanitized = BlockStateCache.sanitize(original);
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

        else if (msg instanceof ClientboundLevelChunkWithLightPacket chunkPacket) {
            ctx.write(msg, promise);

            LevelChunk chunk = ((CraftWorld) player.getWorld()).getHandle().getChunkIfLoaded(chunkPacket.getX(), chunkPacket.getZ());
            if (chunk == null) {
                return null;
            }

            Environment env = player.getWorld().getEnvironment();
            BlockState stone = ObfuscationPalette.getObfuscatedBlock(plugin.getEngineMode(), new BlockPos(0, 1, 0), env);
            BlockState deepslate = ObfuscationPalette.getObfuscatedBlock(plugin.getEngineMode(), new BlockPos(0, -1, 0), env);
            int minBuildHeight = player.getWorld().getMinHeight();

            ctx.channel().eventLoop().execute(() -> {
                try {
                    LevelChunkSection[] sections = chunk.getSections();

                    short[] bufferPos = new short[4096];
                    BlockState[] bufferState = new BlockState[4096];

                    for (int i = 0; i < sections.length; i++) {
                        LevelChunkSection section = sections[i];
                        if (section == null || section.hasOnlyAir()) continue;

                        int sectionY = (minBuildHeight >> 4) + i;
                        int globalSectionY = sectionY << 4;
                        boolean isAggressiveZone = globalSectionY < AGGRESSIVE_Y_MAX;

                        boolean needsObfuscation = section.getStates().maybeHas(state -> {
                            try {
                                int id = Block.getId(state);
                                boolean isSensitive = id >= 0 && id < MetadataStripper.sensitiveGlobal.length && MetadataStripper.sensitiveGlobal[id];
                                if (isSensitive) return true;

                                if (isAggressiveZone) {
                                    return state.isAir() || !state.getFluidState().isEmpty();
                                }
                                return false;
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

                                    boolean isSensitive = id >= 0 && id < MetadataStripper.sensitiveGlobal.length && MetadataStripper.sensitiveGlobal[id];
                                    boolean isCaveFiller = isAggressiveZone && (state.isAir() || !state.getFluidState().isEmpty());

                                    if (isSensitive || isCaveFiller) {
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
     * Safely uninjects the custom duplex handler from the player's network channel.
     *
     * @param player the target player instance
     */
    public void removePlayer(Player player) {
        Channel channel = ((CraftPlayer) player).getHandle().connection.connection.channel;
        channel.eventLoop().submit(() -> {
            if (channel.pipeline().get(HANDLER_NAME) != null) {
                channel.pipeline().remove(HANDLER_NAME);
            }
        });
    }
}