package com.angryguyy.metadatastripper.cache;

import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;

/**
 * Utility class for sanitizing native Mojang BlockStates to strip sensitive metadata
 * that can be exploited by chunk finders and growth-tracking modules.
 */
public class BlockStateCache {

    /**
     * Sanitizes a native Mojang BlockState by resetting age values, berry states, and waterlogging.
     *
     * @param state the original block state
     * @return the modified block state, or the original if no changes were needed
     */
    public static BlockState sanitize(BlockState state) {
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