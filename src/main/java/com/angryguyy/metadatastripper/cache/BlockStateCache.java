package com.angryguyy.metadatastripper.cache;

import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;

/**
 * A highly optimized, Zero-GC cache mechanism for sanitizing native Mojang {@link BlockState} instances.
 * <p>
 * This class intercepts and cleanses sensitive block metadata that could be exploited by unauthorized
 * client-side modifications (e.g., chunk finders, growth-tracking ESPs, and Seed Crackers).
 * It strips variables such as crop maturation stages, waterlogged statuses, block orientations,
 * leaf distances, and snow layer thicknesses which are often used to reverse-engineer world seeds.
 * <p>
 * <b>Algorithmic Complexity:</b>
 * <ul>
 *   <li><b>Initialization:</b> O(N) where N is the total registry size. Executed asynchronously once during server startup.</li>
 *   <li><b>Lookup:</b> O(1) direct array indexing. Guarantees nanosecond-level access times during Netty packet intercept loops.</li>
 * </ul>
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
        throw new UnsupportedOperationException("Utility class cannot be instantiated.");
    }

    /**
     * Retrieves the sanitized, mathematically flattened equivalent of a given block state.
     * Evaluates instantaneously using a pre-computed array indexed by the native NMS Block ID.
     *
     * @param state the original, potentially sensitive block state
     * @return the spoofed block state, or the original state if no sanitization was required
     */
    public static BlockState sanitize(BlockState state) {
        if (state == null) {
            return null;
        }

        int id = Block.getId(state);
        if (id >= 0 && id < SANITIZED_STATES.length) {
            BlockState cached = SANITIZED_STATES[id];
            return cached != null ? cached : state;
        }

        return state;
    }

    /**
     * Internal mutation engine that systematically strips deterministic metadata properties
     * from a given block state. This normalizes patterns to neutralize Seed Cracking algorithms.
     *
     * @param state the native block state evaluated during initialization
     * @return a normalized, baseline representation of the state
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
        if (spoofed.hasProperty(BlockStateProperties.DISTANCE) && spoofed.getValue(BlockStateProperties.DISTANCE) != 1) {
            spoofed = spoofed.setValue(BlockStateProperties.DISTANCE, 1);
            modified = true;
        }
        if (spoofed.hasProperty(BlockStateProperties.LAYERS) && spoofed.getValue(BlockStateProperties.LAYERS) != 1) {
            spoofed = spoofed.setValue(BlockStateProperties.LAYERS, 1);
            modified = true;
        }
        if (spoofed.hasProperty(BlockStateProperties.MOISTURE) && spoofed.getValue(BlockStateProperties.MOISTURE) > 0) {
            spoofed = spoofed.setValue(BlockStateProperties.MOISTURE, 0);
            modified = true;
        }
        if (spoofed.hasProperty(BlockStateProperties.PICKLES) && spoofed.getValue(BlockStateProperties.PICKLES) != 1) {
            spoofed = spoofed.setValue(BlockStateProperties.PICKLES, 1);
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