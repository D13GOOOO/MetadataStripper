package com.angryguyy.metadatastripper.util;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.bukkit.World.Environment;

/**
 * Deterministic palette generator for visual obfuscation.
 * <p>
 * Spoofs standard blocks into clean, uniform background blocks (Stone/Deepslate)
 * to preserve server TPS and client-side rendering performance.
 */
public final class ObfuscationPalette {

    private static final BlockState STONE = Blocks.STONE.defaultBlockState();
    private static final BlockState DEEPSLATE = Blocks.DEEPSLATE.defaultBlockState();
    private static final BlockState NETHERRACK = Blocks.NETHERRACK.defaultBlockState();
    private static final BlockState END_STONE = Blocks.END_STONE.defaultBlockState();

    private ObfuscationPalette() {
        throw new UnsupportedOperationException("Utility class cannot be instantiated");
    }

    /**
     * Generates a deterministic obfuscated block.
     * Always returns clean background blocks (Stone for Y >= 0, Deepslate for Y < 0)
     * to eliminate rendering overhead and maximize FPS/TPS performance.
     *
     * @param engineMode  the configured engine mode
     * @param pos         the block position
     * @param environment the world environment
     * @return the uniform background BlockState
     */
    public static BlockState getObfuscatedBlock(int engineMode, BlockPos pos, Environment environment) {
        if (environment == Environment.THE_END) {
            return END_STONE;
        }

        if (environment == Environment.NETHER) {
            return NETHERRACK;
        }

        return pos.getY() < 0 ? DEEPSLATE : STONE;
    }
}