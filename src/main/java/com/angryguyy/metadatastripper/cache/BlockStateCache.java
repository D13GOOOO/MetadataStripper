package com.angryguyy.metadatastripper.cache;

import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
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

        for (int i = 0; i < maxStates; i++) {
            try {
                BlockState original = Block.stateById(i);
                SANITIZED_STATES[i] = applySanitization(original);
            } catch (Exception e) {
                SANITIZED_STATES[i] = null;
            }
        }
    }

    private BlockStateCache() {
        throw new UnsupportedOperationException("Utility class cannot be instantiated");
    }

    /**
     * Retrieves the sanitized version of a native Mojang BlockState.
     *
     * @param state the original block state to sanitize
     * @return the sanitized block state, or the original if no changes were needed
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

        return state;
    }

    /**
     * Applies strict sanitization logic to strip all player-identifiable block states.
     *
     * @param state the block state to process
     * @return the stripped block state
     */
    private static BlockState applySanitization(BlockState state) {
        BlockState spoofed = state;
        boolean modified = false;

        if (spoofed.hasProperty(BlockStateProperties.AGE_1) && spoofed.getValue(BlockStateProperties.AGE_1) > 0) {
            spoofed = spoofed.setValue(BlockStateProperties.AGE_1, 0);
            modified = true;
        }

        if (spoofed.hasProperty(BlockStateProperties.AGE_2) && spoofed.getValue(BlockStateProperties.AGE_2) > 0) {
            spoofed = spoofed.setValue(BlockStateProperties.AGE_2, 0);
            modified = true;
        }

        if (spoofed.hasProperty(BlockStateProperties.AGE_3) && spoofed.getValue(BlockStateProperties.AGE_3) > 0) {
            spoofed = spoofed.setValue(BlockStateProperties.AGE_3, 0);
            modified = true;
        }

        if (spoofed.hasProperty(BlockStateProperties.AGE_5) && spoofed.getValue(BlockStateProperties.AGE_5) > 0) {
            spoofed = spoofed.setValue(BlockStateProperties.AGE_5, 0);
            modified = true;
        }

        if (spoofed.hasProperty(BlockStateProperties.AGE_7) && spoofed.getValue(BlockStateProperties.AGE_7) > 0) {
            spoofed = spoofed.setValue(BlockStateProperties.AGE_7, 0);
            modified = true;
        }

        if (spoofed.hasProperty(BlockStateProperties.AGE_15) && spoofed.getValue(BlockStateProperties.AGE_15) > 0) {
            spoofed = spoofed.setValue(BlockStateProperties.AGE_15, 0);
            modified = true;
        }

        if (spoofed.hasProperty(BlockStateProperties.AGE_25) && spoofed.getValue(BlockStateProperties.AGE_25) > 0) {
            spoofed = spoofed.setValue(BlockStateProperties.AGE_25, 0);
            modified = true;
        }

        if (spoofed.hasProperty(BlockStateProperties.STAGE) && spoofed.getValue(BlockStateProperties.STAGE) > 0) {
            spoofed = spoofed.setValue(BlockStateProperties.STAGE, 0);
            modified = true;
        }

        if (spoofed.hasProperty(BlockStateProperties.BERRIES) && spoofed.getValue(BlockStateProperties.BERRIES)) {
            spoofed = spoofed.setValue(BlockStateProperties.BERRIES, false);
            modified = true;
        }

        if (spoofed.hasProperty(BlockStateProperties.WATERLOGGED) && spoofed.getValue(BlockStateProperties.WATERLOGGED)) {
            spoofed = spoofed.setValue(BlockStateProperties.WATERLOGGED, false);
            modified = true;
        }

        if (spoofed.getBlock() == Blocks.DEEPSLATE && spoofed.hasProperty(BlockStateProperties.AXIS)) {
            if (spoofed.getValue(BlockStateProperties.AXIS) != Direction.Axis.Y) {
                spoofed = spoofed.setValue(BlockStateProperties.AXIS, Direction.Axis.Y);
                modified = true;
            }
        }

        return modified ? spoofed : state;
    }
}