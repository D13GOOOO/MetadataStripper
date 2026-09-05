package com.angryguyy.metadatastripper.data;

import net.minecraft.core.BlockPos;

/**
 * An immutable Data Transfer Object (DTO) representing the calculated visibility state of a sensitive block.
 * <p>
 * This object is instantiated by the asynchronous Ray-Tracing thread and placed into the lock-free
 * queue ({@link PlayerData#getResults()}). Because all fields are {@code final}, this class is
 * inherently thread-safe. It can be safely passed between the worker threads and the main server
 * thread without any expensive synchronization overhead.
 */
public final class Result {

    /**
     * The wrapper containing the native chunk reference and its obfuscated blocks.
     */
    private final ChunkBlocks chunkBlocks;

    /**
     * The exact native coordinates of the block being updated.
     */
    private final BlockPos block;

    /**
     * The new visibility state of the block.
     * Boolean true = the block should be revealed to the player (real block state).
     * Boolean false = the block should be hidden from the player (spoofed stone/deepslate).
     */
    private final boolean visible;

    /**
     * Constructs a new immutable visibility result.
     *
     * @param chunkBlocks the chunk wrapper containing this block.
     * @param block       the specific coordinates of the block.
     * @param visible     the calculated visibility state based on the ray-trace.
     */
    public Result(ChunkBlocks chunkBlocks, BlockPos block, boolean visible) {
        this.chunkBlocks = chunkBlocks;
        this.block = block;
        this.visible = visible;
    }

    /**
     * Retrieves the chunk wrapper associated with this result.
     *
     * @return the {@link ChunkBlocks} instance.
     */
    public ChunkBlocks getChunkBlocks() {
        return this.chunkBlocks;
    }

    /**
     * Retrieves the block coordinates.
     *
     * @return the {@link BlockPos} of the sensitive block.
     */
    public BlockPos getBlock() {
        return this.block;
    }

    /**
     * Determines whether the block should now be visible or hidden.
     *
     * @return true if visible, false if hidden.
     */
    public boolean isVisible() {
        return this.visible;
    }
}