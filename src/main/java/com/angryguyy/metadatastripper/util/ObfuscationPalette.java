package com.angryguyy.metadatastripper.util;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.bukkit.World.Environment;

/**
 * A deterministic palette generator for subterranean visual obfuscation.
 * <p>
 * Responsible for mapping hidden coordinates into clean, uniform background block states
 * (Stone, Deepslate, Netherrack, or End Stone) depending on the dimensional environment and altitude.
 * This ensures absolute consistency in obfuscation while preventing client-side rendering lag.
 * <p>
 * <b>Algorithmic Complexity:</b>
 * <ul>
 *   <li><b>Resolution:</b> O(1) constant-time evaluation utilizing pre-cached static state references and tableswitch evaluation.</li>
 * </ul>
 */
public final class ObfuscationPalette {

    private static final BlockState STONE = Blocks.STONE.defaultBlockState();
    private static final BlockState DEEPSLATE = Blocks.DEEPSLATE.defaultBlockState();
    private static final BlockState NETHERRACK = Blocks.NETHERRACK.defaultBlockState();
    private static final BlockState END_STONE = Blocks.END_STONE.defaultBlockState();

    /**
     * Private constructor to enforce utility class design patterns.
     *
     * @throws UnsupportedOperationException if instantiation is attempted
     */
    private ObfuscationPalette() {
        throw new UnsupportedOperationException("Utility class cannot be instantiated.");
    }

    /**
     * Determines and returns the appropriate uniform background block state in O(1) time.
     * <p>
     * Evaluates the world environment to return dimension-specific filler blocks (Netherrack for the Nether,
     * End Stone for the End). For normal worlds, it dynamically branches based on the altitude coordinate,
     * returning Deepslate for sub-surface layers (Y &lt; 0) and Stone for upper layers.
     *
     * @param engineMode  the configured operational engine mode (reserved for future palette variants)
     * @param pos         the targeted block position vector
     * @param environment the Bukkit world environment type
     * @return the pre-cached uniform background {@link BlockState}
     */
    @SuppressWarnings("unused")
    public static BlockState getObfuscatedBlock(int engineMode, BlockPos pos, Environment environment) {
        return switch (environment) {
            case THE_END -> END_STONE;
            case NETHER -> NETHERRACK;
            default -> pos.getY() < 0 ? DEEPSLATE : STONE;
        };
    }
}