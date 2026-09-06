package com.angryguyy.metadatastripper.data;

import java.lang.ref.Reference;
import java.lang.ref.WeakReference;

import it.unimi.dsi.fastutil.longs.Long2BooleanMap;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;

/**
 * Represents a wrapper for a Minecraft chunk and its associated sensitive blocks.
 * <p>
 * Uses a {@link WeakReference} to hold the native NMS {@link LevelChunk} to prevent
 * memory leaks. Sensitive block positions are stored as primitive packed longs
 * via FastUtil to guarantee zero garbage collection overhead during massive chunk loading.
 */
public final class ChunkBlocks {

    private final Reference<LevelChunk> chunk;
    private final LongWrapper key;
    private final Long2BooleanMap blocks;

    /**
     * Constructs a new ChunkBlocks wrapper.
     *
     * @param chunk  the native NMS chunk to wrap
     * @param blocks a mutable primitive map of packed block positions to their obfuscation state
     */
    public ChunkBlocks(LevelChunk chunk, Long2BooleanMap blocks) {
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
     * Retrieves the primitive map of sensitive blocks and their obfuscation state.
     *
     * @return a mutable Long2BooleanMap of packed block positions to their hidden state
     */
    public Long2BooleanMap getBlocks() {
        return this.blocks;
    }
}