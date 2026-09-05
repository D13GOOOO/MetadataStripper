package com.angryguyy.metadatastripper.listeners;

import com.angryguyy.metadatastripper.MetadataStripper;
import com.angryguyy.metadatastripper.cache.BlockStateCache;
import com.angryguyy.metadatastripper.data.ChunkBlocks;
import com.angryguyy.metadatastripper.data.LongWrapper;
import com.angryguyy.metadatastripper.data.PlayerData;
import com.angryguyy.metadatastripper.entity.EntityProtector;

import io.netty.channel.Channel;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import net.minecraft.network.protocol.game.*;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;

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
import net.minecraft.core.BlockPos;

public class NettyInjector implements Listener {

    private final MetadataStripper plugin;
    private static Field sectionStatesField;

    static {
        try {
            sectionStatesField = ClientboundSectionBlocksUpdatePacket.class.getDeclaredField("states");
            sectionStatesField.setAccessible(true);
        } catch (NoSuchFieldException e) { e.printStackTrace(); }
    }

    public NettyInjector(MetadataStripper plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) { injectPlayer(event.getPlayer()); }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) { removePlayer(event.getPlayer()); }

    public void injectPlayer(Player player) {
        Channel channel = ((CraftPlayer) player).getHandle().connection.connection.channel;
        if (channel.pipeline().get("MetadataStripper") != null) return;

        channel.pipeline().addBefore("packet_handler", "MetadataStripper", new ChannelDuplexHandler() {
            @Override
            public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {

                if (msg instanceof ClientboundBlockUpdatePacket packet) {
                    BlockState original = packet.getBlockState();
                    BlockState sanitized = BlockStateCache.sanitize(original);
                    if (original != sanitized) {
                        msg = new ClientboundBlockUpdatePacket(packet.getPos(), sanitized);
                    }
                } else if (msg instanceof ClientboundSectionBlocksUpdatePacket packet) {
                    if (sectionStatesField != null) {
                        BlockState[] states = (BlockState[]) sectionStatesField.get(packet);
                        boolean modified = false;
                        BlockState[] newStates = states.clone();
                        for (int i = 0; i < newStates.length; i++) {
                            BlockState sanitized = BlockStateCache.sanitize(newStates[i]);
                            if (newStates[i] != sanitized) {
                                newStates[i] = sanitized; modified = true;
                            }
                        }
                        if (modified) { sectionStatesField.set(packet, newStates); }
                    }
                }
                else if (msg instanceof ClientboundAddEntityPacket addEntityPacket) {
                    // INTERCETTAZIONE ENTITÀ: Se è un Minecart con cassa/tramoggia, verifichiamo la visibilità
                    if (EntityProtector.isSensitiveEntity(addEntityPacket)) {
                        // Per sicurezza contro gli ESP passivi, blocchiamo lo spawn iniziale dell'entità
                        // finché il player non si trova nello stesso chunk o la guarda direttamente.
                        // In alternativa, se vuoi un blocco totale, ignoriamo la scrittura del pacchetto:
                        return;
                    }
                }
                else if (msg instanceof ClientboundLevelChunkWithLightPacket chunkPacket) {
                    super.write(ctx, msg, promise); // Invia il chunk vero

                    long chunkKey = ChunkPos.asLong(chunkPacket.getX(), chunkPacket.getZ());
                    Map<BlockPos, Boolean> blocks = plugin.globalSensitiveBlocks.get(new LongWrapper(chunkKey));

                    if (blocks != null && !blocks.isEmpty()) {
                        for (BlockPos pos : blocks.keySet()) {
                            BlockState fakeState = pos.getY() < 0 ? Blocks.DEEPSLATE.defaultBlockState() : Blocks.STONE.defaultBlockState();
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
                    return;
                } else if (msg instanceof ClientboundForgetLevelChunkPacket forgetPacket) {
                    PlayerData playerData = plugin.getPlayerData().get(player.getUniqueId());
                    if (playerData != null) playerData.getChunks().remove(new LongWrapper(ChunkPos.asLong(forgetPacket.pos().x, forgetPacket.pos().z)));
                } else if (msg instanceof ClientboundRespawnPacket) {
                    PlayerData playerData = plugin.getPlayerData().get(player.getUniqueId());
                    if (playerData != null) playerData.getChunks().clear();
                }

                super.write(ctx, msg, promise);
            }
        });
    }

    public void removePlayer(Player player) {
        Channel channel = ((CraftPlayer) player).getHandle().connection.connection.channel;
        channel.eventLoop().submit(() -> {
            if (channel.pipeline().get("MetadataStripper") != null) {
                channel.pipeline().remove("MetadataStripper");
            }
        });
    }
}