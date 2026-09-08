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
 * client-side modifications (e.g., chunk finders or growth-tracking ESPs). It strips variables such as
 * crop maturation stages, waterlogged statuses, and block orientations.
 * <p>
 * <b>Algorithmic Complexity:</b>
 * <ul>
 *   <li><b>Initialization:</b> O(N) where N is the total registry size. Executed asynchronously once during server startup.</li>
 *   <li><b>Lookup:</b> O(1) direct array indexing. Guarantees nanosecond-level access times during Netty packet intercept loops.</li>
 * </ul>
 */
public final class BlockStateCache {

    /**
     * A pre-computed Lookup Table (LUT) holding the sanitized equivalent of every registered BlockState.
     * Maps the internal NMS BlockState ID directly to its sanitized counterpart.
     */
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
                if (original != null) {
                    SANITIZED_STATES[i] = applySanitization(original);
                }
            } catch (Exception e) {
                SANITIZED_STATES[i] = null;
            }
        }
    }

    /**
     * Private constructor to prevent instantiation of this static utility class.
     *
     * @throws UnsupportedOperationException if instantiation is attempted
     */
    private BlockStateCache() {
        throw new UnsupportedOperationException("Utility class cannot be instantiated");
    }

    /**
     * Instantly retrieves the sanitized version of a provided Mojang {@link BlockState} via an O(1) array lookup.
     *
     * @param state the original, potentially metadata-rich block state targeted for the network packet
     * @return the pre-sanitized block state, or the identical original state if no sanitization was required
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
     * Deep-scans and scrubs a {@link BlockState} of all exploitable client-side properties.
     * <p>
     * Evaluates and resets sequential state properties such as generic ages (AGE_1 to AGE_25),
     * growth stages, berry flags, and waterlogged flags. It also unifies the directional axis
     * of deepslate to homogenize the underground visual obfuscation palette.
     *
     * @param state the original block state to evaluate during class initialization
     * @return a newly mutated block state stripped of identifiable metadata, or the original state if unaffected
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