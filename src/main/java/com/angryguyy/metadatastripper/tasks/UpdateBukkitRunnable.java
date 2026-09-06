package com.angryguyy.metadatastripper.tasks;

import java.util.Queue;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Consumer;

import org.bukkit.World;
import org.bukkit.World.Environment;
import org.bukkit.craftbukkit.CraftWorld;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;

import com.angryguyy.metadatastripper.MetadataStripper;
import com.angryguyy.metadatastripper.data.ChunkBlocks;
import com.angryguyy.metadatastripper.data.EntityResult;
import com.angryguyy.metadatastripper.data.LongWrapper;
import com.angryguyy.metadatastripper.data.PlayerData;
import com.angryguyy.metadatastripper.data.Result;
import com.angryguyy.metadatastripper.util.ObfuscationPalette;

import io.netty.channel.Channel;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/**
 * The primary execution task that synchronizes the asynchronous Ray-Tracing results
 * back to the players on the main server thread (or Folia regional thread).
 * <p>
 * Performance: Employs Network Batch Flushing. Instead of flushing the TCP network channel
 * for every single block update (which severely degrades server TPS and network bandwidth),
 * it buffers all block updates and pushes them in a single native network flush operation.
 */
public final class UpdateBukkitRunnable extends BukkitRunnable implements Consumer<ScheduledTask> {

    private final MetadataStripper plugin;
    private final Player player;

    /**
     * Constructs the task for global server execution (standard Bukkit).
     *
     * @param plugin the main plugin instance
     */
    public UpdateBukkitRunnable(MetadataStripper plugin) {
        this(plugin, null);
    }

    /**
     * Constructs the task for a specific player (Folia regional threading).
     *
     * @param plugin the main plugin instance
     * @param player the specific player to update
     */
    public UpdateBukkitRunnable(MetadataStripper plugin, Player player) {
        this.plugin = plugin;
        this.player = player;
    }

    @Override
    public void run() {
        if (this.player == null) {
            plugin.getServer().getOnlinePlayers().forEach(this::update);
        } else {
            update(this.player);
        }
    }

    @Override
    public void accept(ScheduledTask t) {
        run();
    }

    /**
     * Processes the queue of Ray-Tracer results and pushes the appropriate packets to the client.
     *
     * @param player the player to update
     */
    public void update(Player player) {
        PlayerData playerData = plugin.getPlayerData().get(player.getUniqueId());

        if (!plugin.validatePlayerData(player, playerData, "update")) {
            return;
        }

        World world = playerData.getLocations()[0].getWorld();

        if (!player.getWorld().equals(world)) {
            return;
        }

        Channel channel = getPlayerChannel(player);
        if (channel == null || !channel.isOpen()) {
            return;
        }

        ConcurrentMap<LongWrapper, ChunkBlocks> chunks = playerData.getChunks();
        ServerLevel serverLevel = ((CraftWorld) world).getHandle();
        Environment environment = world.getEnvironment();

        Queue<Result> results = playerData.getResults();
        Queue<EntityResult> entityResults = playerData.getEntityResults();

        Result result;
        boolean requiresFlush = false;
        int engineMode = plugin.getEngineMode();

        while ((result = results.poll()) != null) {
            ChunkBlocks chunkBlocks = result.getChunkBlocks();

            if (chunkBlocks.getChunk() == null || chunks.get(chunkBlocks.getKey()) != chunkBlocks) {
                continue;
            }

            BlockPos block = result.getBlock();

            if (!world.isChunkLoaded(block.getX() >> 4, block.getZ() >> 4)) {
                continue;
            }

            BlockState blockState;
            BlockEntity blockEntity = null;

            if (result.isVisible()) {
                blockState = serverLevel.getBlockState(block);
                if (blockState.hasBlockEntity()) {
                    blockEntity = serverLevel.getBlockEntity(block);
                }
            } else {
                blockState = ObfuscationPalette.getObfuscatedBlock(engineMode, block, environment);
            }

            channel.write(new ClientboundBlockUpdatePacket(block, blockState));
            requiresFlush = true;

            if (blockEntity != null) {
                Packet<ClientGamePacketListener> packet = blockEntity.getUpdatePacket();
                if (packet != null) {
                    channel.write(packet);
                }
            }
        }

        if (requiresFlush) {
            channel.flush();
        }

        EntityResult entityResult;
        while ((entityResult = entityResults.poll()) != null) {
            Entity entity = entityResult.getEntity();

            if (entity == null || !entity.isValid()) {
                continue;
            }

            if (entityResult.isVisible()) {
                player.showEntity(plugin, entity);
            } else {
                player.hideEntity(plugin, entity);
            }
        }
    }

    /**
     * Safely retrieves the player's underlying Netty Channel.
     *
     * @param player the player
     * @return the Channel, or null if unavailable
     */
    private static Channel getPlayerChannel(Player player) {
        try {
            return ((CraftPlayer) player).getHandle().connection.connection.channel;
        } catch (Exception ignored) {
            return null;
        }
    }
}