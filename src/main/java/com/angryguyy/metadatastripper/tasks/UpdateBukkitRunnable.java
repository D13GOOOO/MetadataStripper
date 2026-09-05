package com.angryguyy.metadatastripper.tasks;

import java.util.Queue;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Consumer;

import org.bukkit.World;
import org.bukkit.World.Environment;
import org.bukkit.craftbukkit.CraftWorld;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;

import com.angryguyy.metadatastripper.MetadataStripper;
import com.angryguyy.metadatastripper.data.ChunkBlocks;
import com.angryguyy.metadatastripper.data.LongWrapper;
import com.angryguyy.metadatastripper.data.PlayerData;
import com.angryguyy.metadatastripper.data.Result;

import io.netty.channel.Channel;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
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

    // Pre-calculated BlockStates to avoid thousands of NMS method calls
    private static final BlockState STONE_STATE = Blocks.STONE.defaultBlockState();
    private static final BlockState DEEPSLATE_STATE = Blocks.DEEPSLATE.defaultBlockState();
    private static final BlockState NETHERRACK_STATE = Blocks.NETHERRACK.defaultBlockState();
    private static final BlockState END_STONE_STATE = Blocks.END_STONE.defaultBlockState();

    private final MetadataStripper plugin;
    private final Player player;

    /**
     * Constructs the task for global server execution (standard Bukkit).
     *
     * @param plugin the main plugin instance.
     */
    public UpdateBukkitRunnable(MetadataStripper plugin) {
        this(plugin, null);
    }

    /**
     * Constructs the task for a specific player (Folia regional threading).
     *
     * @param plugin the main plugin instance.
     * @param player the specific player to update.
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

    /**
     * Compatibility method for Folia's ScheduledTask interface.
     */
    @Override
    public void accept(ScheduledTask t) {
        run();
    }

    /**
     * Processes the queue of Ray-Tracer results and pushes the appropriate packets to the client.
     *
     * @param player the player to update.
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

        // Establish network channel
        Channel channel = getPlayerChannel(player);
        if (channel == null || !channel.isOpen()) {
            return;
        }

        ConcurrentMap<LongWrapper, ChunkBlocks> chunks = playerData.getChunks();
        ServerLevel serverLevel = ((CraftWorld) world).getHandle();
        Environment environment = world.getEnvironment();
        Queue<Result> results = playerData.getResults();

        Result result;
        boolean requiresFlush = false;

        // Drain the queue
        while ((result = results.poll()) != null) {
            ChunkBlocks chunkBlocks = result.getChunkBlocks();

            // Skip if the chunk has been garbage collected or replaced
            if (chunkBlocks.getChunk() == null || chunks.get(chunkBlocks.getKey()) != chunkBlocks) {
                continue;
            }

            BlockPos block = result.getBlock();

            // Safety verification that the chunk is still active before retrieving native data
            if (!world.isChunkLoaded(block.getX() >> 4, block.getZ() >> 4)) {
                continue;
            }

            BlockState blockState;
            BlockEntity blockEntity = null;

            if (result.isVisible()) {
                // Fetch the real, unobfuscated block
                blockState = serverLevel.getBlockState(block);
                if (blockState.hasBlockEntity()) {
                    blockEntity = serverLevel.getBlockEntity(block);
                }
            } else if (environment == Environment.NETHER) {
                blockState = NETHERRACK_STATE;
            } else if (environment == Environment.THE_END) {
                blockState = END_STONE_STATE;
            } else if (block.getY() < 0) {
                blockState = DEEPSLATE_STATE;
            } else {
                blockState = STONE_STATE;
            }

            // WRITE ONLY: Queues the packet in the channel buffer (CPU cost is practically zero)
            channel.write(new ClientboundBlockUpdatePacket(block, blockState));
            requiresFlush = true;

            // If it's a real block with NBT data (like a chest), queue its data packet too
            if (blockEntity != null) {
                Packet<ClientGamePacketListener> packet = blockEntity.getUpdatePacket();
                if (packet != null) {
                    channel.write(packet);
                }
            }
        }

        // FLUSH ONCE: Push all buffered packets to the network card in a single, ultra-fast operation
        if (requiresFlush) {
            channel.flush();
        }
    }

    /**
     * Safely retrieves the player's underlying Netty Channel.
     *
     * @param player the player.
     * @return the Channel, or null if unavailable.
     */
    private static Channel getPlayerChannel(Player player) {
        try {
            return ((CraftPlayer) player).getHandle().connection.connection.channel;
        } catch (Exception e) {
            return null;
        }
    }
}