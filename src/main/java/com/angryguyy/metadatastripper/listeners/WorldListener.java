package com.angryguyy.metadatastripper.listeners;

import java.util.HashMap;
import java.util.Map;

import org.bukkit.craftbukkit.CraftWorld;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.event.world.ChunkUnloadEvent;

import com.angryguyy.metadatastripper.MetadataStripper;
import com.angryguyy.metadatastripper.data.LongWrapper;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;

/**
 * Listens for chunk load and unload events to index sensitive blocks.
 * <p>
 * Performance: The chunk scanning algorithm is highly optimized for extreme TPS preservation.
 * It uses native NMS block state retrieval and local primitive array caching to bypass Bukkit API
 * overhead. The iteration loops are strictly ordered (Y, Z, X) to align with Minecraft's internal
 * memory spatial locality, drastically reducing CPU cache misses during chunk generation.
 */
public final class WorldListener implements Listener {

    private final MetadataStripper plugin;

    private static final int STONE_ID = Block.getId(Blocks.STONE.defaultBlockState());
    private static final int DEEPSLATE_ID = Block.getId(Blocks.DEEPSLATE.defaultBlockState());
    private static final int NETHERRACK_ID = Block.getId(Blocks.NETHERRACK.defaultBlockState());

    /**
     * Constructs the WorldListener.
     *
     * @param plugin the main plugin instance
     */
    public WorldListener(MetadataStripper plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onChunkLoad(ChunkLoadEvent event) {
        if (plugin.getIgnoredWorlds().contains(event.getWorld().getName())) {
            return;
        }

        int cx = event.getChunk().getX();
        int cz = event.getChunk().getZ();
        ServerLevel serverLevel = ((CraftWorld) event.getWorld()).getHandle();
        LevelChunk chunk = serverLevel.getChunkIfLoaded(cx, cz);

        if (chunk == null) {
            return;
        }

        Map<BlockPos, Boolean> sensitiveBlocks = new HashMap<>();
        int minSy = event.getWorld().getMinHeight() >> 4;
        LevelChunkSection[] sections = chunk.getSections();
        boolean[] sensitiveGlobal = MetadataStripper.sensitiveGlobal;
        int maxId = sensitiveGlobal.length;

        int baseX = cx << 4;
        int baseZ = cz << 4;

        int engineMode = plugin.getEngineMode();
        int fakePercentage = plugin.getFakeOrePercentage();

        for (int i = 0; i < sections.length; i++) {
            LevelChunkSection section = sections[i];

            if (section == null || section.hasOnlyAir()) {
                continue;
            }

            int startY = (minSy + i) << 4;

            for (int y = 0; y < 16; y++) {
                for (int z = 0; z < 16; z++) {
                    for (int x = 0; x < 16; x++) {
                        BlockState state = section.getBlockState(x, y, z);
                        int id = Block.getId(state);

                        if (id >= 0 && id < maxId) {
                            if (sensitiveGlobal[id]) {
                                sensitiveBlocks.put(new BlockPos(baseX + x, startY + y, baseZ + z), true);
                            } else if (engineMode == 2 && (id == STONE_ID || id == DEEPSLATE_ID || id == NETHERRACK_ID)) {
                                if (Math.abs((baseX + x) * 31 + (startY + y) * 17 + (baseZ + z)) % 100 < fakePercentage) {
                                    sensitiveBlocks.put(new BlockPos(baseX + x, startY + y, baseZ + z), true);
                                }
                            }
                        }
                    }
                }
            }
        }

        if (!sensitiveBlocks.isEmpty()) {
            plugin.globalSensitiveBlocks.put(new LongWrapper(ChunkPos.asLong(cx, cz)), sensitiveBlocks);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onChunkUnload(ChunkUnloadEvent event) {
        plugin.globalSensitiveBlocks.remove(new LongWrapper(ChunkPos.asLong(event.getChunk().getX(), event.getChunk().getZ())));
    }
}