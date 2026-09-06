package com.angryguyy.metadatastripper.util;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.bukkit.World.Environment;

/**
 * Deterministic palette generator for visual hallucinations (Engine Mode 2).
 * <p>
 * Uses spatial coordinates to consistently spoof standard blocks into fake ores.
 */
public final class ObfuscationPalette {

    private static final BlockState STONE = Blocks.STONE.defaultBlockState();
    private static final BlockState DEEPSLATE = Blocks.DEEPSLATE.defaultBlockState();
    private static final BlockState NETHERRACK = Blocks.NETHERRACK.defaultBlockState();
    private static final BlockState END_STONE = Blocks.END_STONE.defaultBlockState();

    private static final BlockState[] STONE_ORES = {
            Blocks.DIAMOND_ORE.defaultBlockState(), Blocks.GOLD_ORE.defaultBlockState(),
            Blocks.IRON_ORE.defaultBlockState(), Blocks.EMERALD_ORE.defaultBlockState(),
            Blocks.LAPIS_ORE.defaultBlockState(), Blocks.COAL_ORE.defaultBlockState()
    };

    private static final BlockState[] DEEPSLATE_ORES = {
            Blocks.DEEPSLATE_DIAMOND_ORE.defaultBlockState(), Blocks.DEEPSLATE_GOLD_ORE.defaultBlockState(),
            Blocks.DEEPSLATE_IRON_ORE.defaultBlockState(), Blocks.DEEPSLATE_EMERALD_ORE.defaultBlockState(),
            Blocks.DEEPSLATE_LAPIS_ORE.defaultBlockState(), Blocks.DEEPSLATE_REDSTONE_ORE.defaultBlockState()
    };

    private static final BlockState NETHER_QUARTZ = Blocks.NETHER_QUARTZ_ORE.defaultBlockState();
    private static final BlockState NETHER_GOLD = Blocks.NETHER_GOLD_ORE.defaultBlockState();

    private ObfuscationPalette() {
        throw new UnsupportedOperationException("Utility class cannot be instantiated");
    }

    /**
     * Generates a deterministic obfuscated block.
     * Mode 1: Hides the block (returns Stone/Deepslate).
     * Mode 2: Spoofs the block (returns fake Ores).
     *
     * @param engineMode  the configured engine mode
     * @param pos         the block position
     * @param environment the world environment
     * @return the deterministic spoofed BlockState
     */
    public static BlockState getObfuscatedBlock(int engineMode, BlockPos pos, Environment environment) {
        if (environment == Environment.THE_END) {
            return END_STONE;
        }

        if (environment == Environment.NETHER) {
            if (engineMode == 1) {
                return NETHERRACK;
            }
            return ((pos.getX() * 31 + pos.getY() * 17 + pos.getZ()) & 1) == 0 ? NETHER_QUARTZ : NETHER_GOLD;
        }

        if (engineMode == 1) {
            return pos.getY() < 0 ? DEEPSLATE : STONE;
        }

        int hash = (pos.getX() * 73856093 ^ pos.getY() * 19349663 ^ pos.getZ() * 83492791) & 0x7FFFFFFF;
        if (pos.getY() < 0) {
            return DEEPSLATE_ORES[hash % DEEPSLATE_ORES.length];
        } else {
            return STONE_ORES[hash % STONE_ORES.length];
        }
    }
}