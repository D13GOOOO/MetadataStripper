package com.angryguyy.metadatastripper.listeners;

import com.angryguyy.metadatastripper.cache.BlockStateCache;
import io.netty.channel.Channel;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundSectionBlocksUpdatePacket;
import net.minecraft.world.level.block.state.BlockState;
import org.bukkit.Bukkit;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.lang.reflect.Field;
import java.util.logging.Logger;

/**
 * Handles Netty channel pipeline injection to intercept and modify outgoing packets
 * specifically for stripping block metadata (growth age) to defeat Sus Chunk Finders.
 */
public class NettyInjector implements Listener {

    private static Field sectionStatesField;
    private static final Logger LOGGER = Logger.getLogger(NettyInjector.class.getName());

    static {
        try {
            sectionStatesField = ClientboundSectionBlocksUpdatePacket.class.getDeclaredField("states");
            sectionStatesField.setAccessible(true);
        } catch (NoSuchFieldException e) {
            LOGGER.severe("MetadataStripper: Could not find states field - " + e.getMessage());
        }
    }

    /**
     * Injects the packet listener pipeline when a player joins the server.
     *
     * @param event the player join event
     */
    @SuppressWarnings("unused")
    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        injectPlayer(event.getPlayer());
    }

    /**
     * Removes the packet listener pipeline when a player leaves the server.
     *
     * @param event the player quit event
     */
    @SuppressWarnings("unused")
    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        removePlayer(event.getPlayer());
    }

    /**
     * Injects a custom duplex handler into the player's Netty pipeline.
     *
     * @param player the target player
     */
    public void injectPlayer(Player player) {
        Channel channel = ((CraftPlayer) player).getHandle().connection.connection.channel;
        if (channel.pipeline().get("MetadataStripper") != null) return;

        channel.pipeline().addBefore("packet_handler", "MetadataStripper", new ChannelDuplexHandler() {
            @Override
            public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {

                // 1. Single Block Update Stripper
                if (msg instanceof ClientboundBlockUpdatePacket packet) {
                    BlockState original = packet.getBlockState();
                    BlockState sanitized = BlockStateCache.sanitize(original);
                    if (original != sanitized) {
                        msg = new ClientboundBlockUpdatePacket(packet.getPos(), sanitized);
                    }
                }
                // 2. Multi-Block Section Stripper
                else if (msg instanceof ClientboundSectionBlocksUpdatePacket packet) {
                    if (sectionStatesField != null) {
                        BlockState[] states = (BlockState[]) sectionStatesField.get(packet);
                        boolean modified = false;
                        BlockState[] newStates = states.clone();

                        for (int i = 0; i < newStates.length; i++) {
                            BlockState original = newStates[i];
                            BlockState sanitized = BlockStateCache.sanitize(original);
                            if (original != sanitized) {
                                newStates[i] = sanitized;
                                modified = true;
                            }
                        }
                        if (modified) {
                            sectionStatesField.set(packet, newStates);
                        }
                    }
                }

                ctx.write(msg, promise);
            }
        });
    }

    /**
     * Safely removes the custom duplex handler from the player's Netty pipeline.
     *
     * @param player the target player
     */
    public void removePlayer(Player player) {
        Channel channel = ((CraftPlayer) player).getHandle().connection.connection.channel;
        channel.eventLoop().submit(() -> {
            if (channel.pipeline().get("MetadataStripper") != null) {
                channel.pipeline().remove("MetadataStripper");
            }
        });
    }
}