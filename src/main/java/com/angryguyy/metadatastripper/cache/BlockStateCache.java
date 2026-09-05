package com.angryguyy.metadatastripper.cache;

import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;

/**
 * Cache for sanitizing native Mojang BlockStates.
 * <p>
 * Strips sensitive metadata (such as crop age, berry presence, or waterlogged status)
 * that could be exploited by chunk finders or growth-tracking modules.
 * <p>
 * Performance: Utilizes a pre-calculated Lookup Table (LUT) on server startup to guarantee
 * O(1) access time. This completely eliminates CPU overhead and object instantiation
 * during heavy Netty packet interception, preserving server TPS.
 */
public final class BlockStateCache {

    private static final BlockState[] SANITIZED_STATES;

    static {
        int maxStates = 100000;
        try {
            maxStates = Block.BLOCK_STATE_REGISTRY.size();
        } catch (Exception ignored) {}

        SANITIZED_STATES = new BlockState[maxStates];

        // Pre-calculates the sanitized version of every possible block state at startup.
        for (int i = 0; i < maxStates; i++) {
            try {
                BlockState original = Block.stateById(i);
                if (original != null) {
                    SANITIZED_STATES[i] = applySanitization(original);
                }
            } catch (Exception e) {
                SANITIZED_STATES[i] = null;
            }
        }
    }

    /**
     * Private constructor to prevent instantiation of this utility class.
     */
    private BlockStateCache() {
        throw new UnsupportedOperationException("Utility class cannot be instantiated");
    }

    /**
     * Instantly retrieves the sanitized version of a native Mojang BlockState.
     *
     * @param state The original block state to sanitize.
     * @return The sanitized block state, or the original if no changes were needed.
     */
    public static BlockState sanitize(BlockState state) {
        if (state == null) {
            return null;
        }
        try {
            int id = Block.getId(state);
            if (id >= 0 && id < SANITIZED_STATES.length) {
                BlockState cached = SANITIZED_STATES[id];
                return cached != null ? cached : state;
            }
        } catch (Exception ignored) {}

        return state; // Fallback in case of out-of-bounds or unregistered blocks
    }

    /**
     * Core sanitization logic applied heavily only once during server startup.
     *
     * @param state The block state to process.
     * @return The stripped block state.
     */
    private static BlockState applySanitization(BlockState state) {
        BlockState spoofed = state;
        boolean modified = false;

        if (spoofed.hasProperty(BlockStateProperties.AGE_25)) {
            if (spoofed.getValue(BlockStateProperties.AGE_25) > 0) {
                spoofed = spoofed.setValue(BlockStateProperties.AGE_25, 0);
                modified = true;
            }
        }

        if (spoofed.hasProperty(BlockStateProperties.AGE_3)) {
            if (spoofed.getValue(BlockStateProperties.AGE_3) > 0) {
                spoofed = spoofed.setValue(BlockStateProperties.AGE_3, 0);
                modified = true;
            }
        }

        if (spoofed.hasProperty(BlockStateProperties.BERRIES)) {
            if (spoofed.getValue(BlockStateProperties.BERRIES)) {
                spoofed = spoofed.setValue(BlockStateProperties.BERRIES, false);
                modified = true;
            }
        }

        if (spoofed.hasProperty(BlockStateProperties.WATERLOGGED)) {
            if (spoofed.getValue(BlockStateProperties.WATERLOGGED)) {
                spoofed = spoofed.setValue(BlockStateProperties.WATERLOGGED, false);
                modified = true;
            }
        }

        return modified ? spoofed : state;
    }
}