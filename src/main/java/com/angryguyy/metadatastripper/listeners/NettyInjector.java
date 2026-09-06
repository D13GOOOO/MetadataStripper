package com.angryguyy.metadatastripper.listeners;

import com.angryguyy.metadatastripper.MetadataStripper;
import com.angryguyy.metadatastripper.cache.BlockStateCache;
import com.angryguyy.metadatastripper.data.ChunkBlocks;
import com.angryguyy.metadatastripper.data.LongWrapper;
import com.angryguyy.metadatastripper.data.PlayerData;
import com.angryguyy.metadatastripper.entity.EntityProtector;
import com.angryguyy.metadatastripper.util.ObfuscationPalette;

import io.netty.channel.Channel;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.game.*;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;

import org.bukkit.World.Environment;
import org.bukkit.craftbukkit.CraftWorld;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.Map;
import java.util.logging.Level;

/**
 * The core network interception module.
 * <p>
 * This class injects a custom {@link ChannelDuplexHandler} into every player's Netty pipeline.
 * It intercepts outbound packets to strip sensitive block metadata, obfuscate exposed ores/chests
 * during chunk loading, and block unauthorized entity spawns.
 * <p>
 * Performance note: Code in this pipeline runs on the Netty EventLoop threads. It is highly
 * optimized to avoid object allocation and heavy computations to preserve server TPS.
 */
public final class NettyInjector implements Listener {

    private static final String HANDLER_NAME = "MetadataStripper";
    private static Field sectionStatesField;

    static {
        try {
            sectionStatesField = ClientboundSectionBlocksUpdatePacket.class.getDeclaredField("states");
            sectionStatesField.setAccessible(true);
        } catch (NoSuchFieldException ignored) {}
    }

    private final MetadataStripper plugin;

    /**
     * Constructs the NettyInjector.
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
     * Injects the custom packet interceptor into the player's network channel.
     *
     * @param player the player to inject
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
     * Processes individual outbound packets, applying obfuscation and metadata stripping.
     *
     * @param ctx     the channel handler context
     * @param player  the target player
     * @param msg     the packet being sent
     * @param promise the channel promise
     * @return the modified packet, or null if the packet should be cancelled
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
        else if (msg instanceof ClientboundAddEntityPacket packet) {
            if (EntityProtector.isSensitiveEntity(packet)) {
                return null;
            }
        }
        else if (msg instanceof ClientboundLevelChunkWithLightPacket chunkPacket) {
            ctx.write(msg, promise);

            long chunkKey = ChunkPos.asLong(chunkPacket.getX(), chunkPacket.getZ());
            Map<BlockPos, Boolean> blocks = plugin.globalSensitiveBlocks.get(new LongWrapper(chunkKey));

            if (blocks != null && !blocks.isEmpty()) {
                int engineMode = plugin.getEngineMode();
                Environment env = player.getWorld().getEnvironment();

                for (BlockPos pos : blocks.keySet()) {
                    BlockState fakeState = ObfuscationPalette.getObfuscatedBlock(engineMode, pos, env);
                    ctx.write(new ClientboundBlockUpdatePacket(pos, fakeState));
                }

                PlayerData playerData = plugin.getPlayerData().get(player.getUniqueId());
                if (playerData != null) {
                    LevelChunk chunk = ((CraftWorld) player.getWorld()).getHandle().getChunkIfLoaded(chunkPacket.getX(), chunkPacket.getZ());
                    if (chunk != null) {
                        ChunkBlocks chunkBlocks = new ChunkBlocks(chunk, new HashMap<>(blocks));
                        playerData.getChunks().put(chunkBlocks.getKey(), chunkBlocks);
                    }
                }
            }
            return null;
        }
        else if (msg instanceof ClientboundForgetLevelChunkPacket forgetPacket) {
            PlayerData playerData = plugin.getPlayerData().get(player.getUniqueId());
            if (playerData != null) {
                playerData.getChunks().remove(new LongWrapper(ChunkPos.asLong(forgetPacket.pos().x, forgetPacket.pos().z)));
            }
        }
        else if (msg instanceof ClientboundRespawnPacket) {
            PlayerData playerData = plugin.getPlayerData().get(player.getUniqueId());
            if (playerData != null) {
                playerData.getChunks().clear();
            }
        }

        return msg;
    }

    /**
     * Removes the custom packet interceptor from the player's network channel.
     *
     * @param player the player to remove
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