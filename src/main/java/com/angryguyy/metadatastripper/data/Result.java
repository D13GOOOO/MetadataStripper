package com.angryguyy.metadatastripper.data;

import net.minecraft.core.BlockPos;

/**
 * An immutable Data Transfer Object (DTO) representing the calculated visibility state of a sensitive block.
 * <p>
 * This object is instantiated by the asynchronous Ray-Tracing thread and placed into the lock-free
 * queue. Because it is a record, it is inherently thread-safe and can be safely passed between
 * the worker threads and the main server thread without any expensive synchronization overhead.
 *
 * @param chunkBlocks the chunk wrapper containing this block
 * @param block       the specific coordinates of the block
 * @param visible     the calculated visibility state based on the ray-trace
 */
public record Result(ChunkBlocks chunkBlocks, BlockPos block, boolean visible) {

    /**
     * Retrieves the chunk wrapper associated with this result.
     *
     * @return the {@link ChunkBlocks} instance
     */
    public ChunkBlocks getChunkBlocks() {
        return this.chunkBlocks;
    }

    /**
     * Retrieves the block coordinates.
     *
     * @return the {@link BlockPos} of the sensitive block
     */
    public BlockPos getBlock() {
        return this.block;
    }

    /**
     * Determines whether the block should now be visible or hidden.
     *
     * @return true if visible, false if hidden
     */
    public boolean isVisible() {
        return this.visible;
    }
}