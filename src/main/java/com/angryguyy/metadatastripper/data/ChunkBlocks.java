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
 * Uses a {@link WeakReference} to hold the native NMS {@link LevelChunk}. This is a critical
 * memory management design choice: it allows the Java Garbage Collector to safely
 * unload and free chunks from RAM without this cache causing Memory Leaks (OOM exceptions).
 */
public final class ChunkBlocks {

    /**
     * Weakly references the native Minecraft chunk to prevent memory leaks when the chunk unloads.
     */
    private final Reference<LevelChunk> chunk;

    /**
     * The fast-access packed coordinate key (x, z) of the chunk.
     */
    private final LongWrapper key;

    /**
     * A map representing sensitive block coordinates within the chunk and their obfuscation state.
     * Boolean true = block is currently hidden (spoofed as stone/deepslate).
     * Boolean false = block is currently revealed to the player.
     */
    private final Map<BlockPos, Boolean> blocks;

    /**
     * Constructs a new ChunkBlocks wrapper.
     *
     * @param chunk  the native NMS chunk to wrap.
     * @param blocks a mutable map of sensitive block positions and their current obfuscation state.
     */
    public ChunkBlocks(LevelChunk chunk, Map<BlockPos, Boolean> blocks) {
        this.chunk = new WeakReference<>(chunk);
        this.key = new LongWrapper(ChunkPos.asLong(chunk.getPos().x, chunk.getPos().z));
        this.blocks = blocks;
    }

    /**
     * Retrieves the underlying chunk if it is still loaded in memory.
     *
     * @return the NMS {@link LevelChunk}, or null if the GC has already cleared it.
     */
    public LevelChunk getChunk() {
        return this.chunk.get();
    }

    /**
     * Retrieves the packed long wrapper key for this chunk.
     *
     * @return the {@link LongWrapper} representing the chunk's packed coordinates.
     */
    public LongWrapper getKey() {
        return this.key;
    }

    /**
     * Retrieves the map of sensitive blocks and their obfuscation state.
     * Note: This map is actively modified by the asynchronous ray-tracing thread.
     *
     * @return a mutable map of block positions to their hidden state (true = hidden).
     */
    public Map<BlockPos, Boolean> getBlocks() {
        return this.blocks;
    }
}