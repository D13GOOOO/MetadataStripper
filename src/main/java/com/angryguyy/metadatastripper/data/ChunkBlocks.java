package com.angryguyy.metadatastripper.data;

import java.lang.ref.Reference;
import java.lang.ref.WeakReference;
import java.util.Map;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;

/**
 * Represents a wrapper for a Minecraft chunk and its associated sensitive blocks.
 * <p>
 * Uses a {@link WeakReference} to hold the native NMS {@link LevelChunk} to prevent
 * memory leaks when chunks are unloaded and garbage collected by the server.
 */
public final class ChunkBlocks {

    private final Reference<LevelChunk> chunk;
    private final LongWrapper key;
    private final Map<BlockPos, Boolean> blocks;

    /**
     * Constructs a new ChunkBlocks wrapper.
     *
     * @param chunk  the native NMS chunk to wrap
     * @param blocks a mutable map of sensitive block positions and their current obfuscation state
     */
    public ChunkBlocks(LevelChunk chunk, Map<BlockPos, Boolean> blocks) {
        this.chunk = new WeakReference<>(chunk);
        ChunkPos pos = chunk.getPos();
        this.key = new LongWrapper(ChunkPos.asLong(pos.x, pos.z));
        this.blocks = blocks;
    }

    /**
     * Retrieves the underlying chunk if it is still loaded in memory.
     *
     * @return the NMS {@link LevelChunk}, or null if garbage collected
     */
    public LevelChunk getChunk() {
        return this.chunk.get();
    }

    /**
     * Retrieves the packed long wrapper key for this chunk.
     *
     * @return the {@link LongWrapper} representing the chunk's packed coordinates
     */
    public LongWrapper getKey() {
        return this.key;
    }

    /**
     * Retrieves the map of sensitive blocks and their obfuscation state.
     *
     * @return a mutable map of block positions to their hidden state
     */
    public Map<BlockPos, Boolean> getBlocks() {
        return this.blocks;
    }
}