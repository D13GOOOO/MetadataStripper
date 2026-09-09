package com.angryguyy.metadatastripper.cache;

import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;

/**
 * A startup-built cache for sanitizing native Mojang {@link BlockState} instances.
 * <p>
 * This cache intercepts and cleanses sensitive block metadata that could be exploited by unauthorized
 * client-side modifications, such as chunk finders, growth-tracking ESPs, and world seed crackers.
 * It systematically strips deterministic variables including crop maturation stages, waterlogged statuses,
 * leaf distances, and snow layer thicknesses.
 * <p>
 * <b>Algorithmic Complexity:</b>
 * <ul>
 *   <li><b>Initialization:</b> O(N) where N is the total block state registry size. Executed synchronously during plugin startup.</li>
 *   <li><b>Lookup:</b> O(1) direct array indexing with no intentional per-lookup allocation.</li>
 * </ul>
 */
public final class BlockStateCache {

    /**
     * Pre-computed array mapping native NMS Block IDs to their sanitized equivalents.
     * Null values within the array indicate that the original block state requires no sanitization.
     */
    private static final BlockState[] SANITIZED_STATES;

    static {
        int registrySize = 100000;
        try {
            registrySize = Block.BLOCK_STATE_REGISTRY.size() + 1000;
        } catch (Exception ignored) {}

        SANITIZED_STATES = new BlockState[registrySize];

        for (int i = 0; i < registrySize; i++) {
            try {
                BlockState original = Block.stateById(i);
                if (original != null) {
                    BlockState sanitized = applySanitization(original);
                    SANITIZED_STATES[i] = (sanitized != original) ? sanitized : null;
                }
            } catch (Exception e) {
                SANITIZED_STATES[i] = null;
            }
        }
    }

    private BlockStateCache() {
        throw new UnsupportedOperationException("Utility class cannot be instantiated.");
    }

    /**
     * Retrieves the sanitized, mathematically flattened equivalent of a given block state.
     * <p>
    * Evaluates using a pre-computed array indexed by the native NMS Block ID.
     *
     * @param state the original, potentially sensitive native block state
     * @return the normalized block state, or the original state if no sanitization is required
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
     * Internal mutation engine that systematically strips deterministic metadata properties
     * from a given block state. This normalizes patterns to neutralize reverse-engineering algorithms.
     *
     * @param state the native block state evaluated during initialization
     * @return a normalized, baseline representation of the block state
     */
    private static BlockState applySanitization(BlockState state) {
        BlockState spoofed = state;

        if (spoofed.hasProperty(BlockStateProperties.AGE_1) && spoofed.getValue(BlockStateProperties.AGE_1) > 0) {
            spoofed = spoofed.setValue(BlockStateProperties.AGE_1, 0);
        }
        if (spoofed.hasProperty(BlockStateProperties.AGE_2) && spoofed.getValue(BlockStateProperties.AGE_2) > 0) {
            spoofed = spoofed.setValue(BlockStateProperties.AGE_2, 0);
        }
        if (spoofed.hasProperty(BlockStateProperties.AGE_3) && spoofed.getValue(BlockStateProperties.AGE_3) > 0) {
            spoofed = spoofed.setValue(BlockStateProperties.AGE_3, 0);
        }
        if (spoofed.hasProperty(BlockStateProperties.AGE_5) && spoofed.getValue(BlockStateProperties.AGE_5) > 0) {
            spoofed = spoofed.setValue(BlockStateProperties.AGE_5, 0);
        }
        if (spoofed.hasProperty(BlockStateProperties.AGE_7) && spoofed.getValue(BlockStateProperties.AGE_7) > 0) {
            spoofed = spoofed.setValue(BlockStateProperties.AGE_7, 0);
        }
        if (spoofed.hasProperty(BlockStateProperties.AGE_15) && spoofed.getValue(BlockStateProperties.AGE_15) > 0) {
            spoofed = spoofed.setValue(BlockStateProperties.AGE_15, 0);
        }
        if (spoofed.hasProperty(BlockStateProperties.AGE_25) && spoofed.getValue(BlockStateProperties.AGE_25) > 0) {
            spoofed = spoofed.setValue(BlockStateProperties.AGE_25, 0);
        }
        if (spoofed.hasProperty(BlockStateProperties.STAGE) && spoofed.getValue(BlockStateProperties.STAGE) > 0) {
            spoofed = spoofed.setValue(BlockStateProperties.STAGE, 0);
        }
        if (spoofed.hasProperty(BlockStateProperties.BERRIES) && spoofed.getValue(BlockStateProperties.BERRIES)) {
            spoofed = spoofed.setValue(BlockStateProperties.BERRIES, false);
        }
        if (spoofed.hasProperty(BlockStateProperties.WATERLOGGED) && spoofed.getValue(BlockStateProperties.WATERLOGGED)) {
            spoofed = spoofed.setValue(BlockStateProperties.WATERLOGGED, false);
        }
        if (spoofed.hasProperty(BlockStateProperties.DISTANCE) && spoofed.getValue(BlockStateProperties.DISTANCE) != 1) {
            spoofed = spoofed.setValue(BlockStateProperties.DISTANCE, 1);
        }
        if (spoofed.hasProperty(BlockStateProperties.LAYERS) && spoofed.getValue(BlockStateProperties.LAYERS) != 1) {
            spoofed = spoofed.setValue(BlockStateProperties.LAYERS, 1);
        }
        if (spoofed.hasProperty(BlockStateProperties.MOISTURE) && spoofed.getValue(BlockStateProperties.MOISTURE) > 0) {
            spoofed = spoofed.setValue(BlockStateProperties.MOISTURE, 0);
        }
        if (spoofed.hasProperty(BlockStateProperties.PICKLES) && spoofed.getValue(BlockStateProperties.PICKLES) != 1) {
            spoofed = spoofed.setValue(BlockStateProperties.PICKLES, 1);
        }
        if (spoofed.getBlock() == Blocks.DEEPSLATE && spoofed.hasProperty(BlockStateProperties.AXIS)) {
            if (spoofed.getValue(BlockStateProperties.AXIS) != Direction.Axis.Y) {
                spoofed = spoofed.setValue(BlockStateProperties.AXIS, Direction.Axis.Y);
            }
        }

        return spoofed;
    }
}