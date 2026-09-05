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
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;

/**
 * Listens for chunk load and unload events to index sensitive blocks (e.g., chests, spawners).
 * <p>
 * Performance: The chunk scanning algorithm is highly optimized for extreme TPS preservation.
 * It uses native NMS block state retrieval and local primitive array caching to bypass Bukkit API
 * overhead. The iteration loops are strictly ordered (Y, Z, X) to align with Minecraft's internal
 * memory spatial locality, drastically reducing CPU cache misses during chunk generation.
 */
public final class WorldListener implements Listener {

    private final MetadataStripper plugin;

    /**
     * Constructs the WorldListener.
     *
     * @param plugin the main plugin instance.
     */
    public WorldListener(MetadataStripper plugin) {
        this.plugin = plugin;
    }

    /**
     * Scans the incoming chunk for sensitive blocks to add to the global obfuscation map.
     * Uses MONITOR priority to ensure we only scan chunks that actually loaded successfully.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onChunkLoad(ChunkLoadEvent event) {
        int cx = event.getChunk().getX();
        int cz = event.getChunk().getZ();
        ServerLevel serverLevel = ((CraftWorld) event.getWorld()).getHandle();
        LevelChunk chunk = serverLevel.getChunkIfLoaded(cx, cz);

        if (chunk == null) return;

        Map<BlockPos, Boolean> sensitiveBlocks = new HashMap<>();

        // Secure Bukkit API for world height bounds
        int minSy = event.getWorld().getMinHeight() >> 4;

        LevelChunkSection[] sections = chunk.getSections();

        // Caching the global boolean array locally for ultra-fast L1 CPU cache access
        boolean[] sensitiveGlobal = MetadataStripper.sensitiveGlobal;
        int maxId = sensitiveGlobal.length;

        // Bitwise shifts (<< 4) are immensely faster than multiplication (* 16)
        // Calculated once per chunk instead of 98,000 times inside the loop
        int baseX = cx << 4;
        int baseZ = cz << 4;

        for (int i = 0; i < sections.length; i++) {
            LevelChunkSection section = sections[i];

            if (section == null || section.hasOnlyAir()) continue;

            int startY = (minSy + i) << 4;

            /*
             * Y, Z, X loop order maximizes CPU cache spatial locality.
             * Minecraft stores PalettedContainers sequentially in this exact order.
             */
            for (int y = 0; y < 16; y++) {
                for (int z = 0; z < 16; z++) {
                    for (int x = 0; x < 16; x++) {
                        BlockState state = section.getBlockState(x, y, z);
                        int id = Block.getId(state);

                        // Bounds check and instant array lookup.
                        // Removed the destructive try-catch block to allow the JIT compiler to inline this loop.
                        if (id >= 0 && id < maxId && sensitiveGlobal[id]) {
                            // Boolean.TRUE avoids continuous Boolean object boxing/allocations
                            sensitiveBlocks.put(new BlockPos(baseX + x, startY + y, baseZ + z), Boolean.TRUE);
                        }
                    }
                }
            }
        }

        if (!sensitiveBlocks.isEmpty()) {
            plugin.globalSensitiveBlocks.put(new LongWrapper(ChunkPos.asLong(cx, cz)), sensitiveBlocks);
        }
    }

    /**
     * Cleans up the memory map when a chunk is unloaded, completely preventing memory leaks.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onChunkUnload(ChunkUnloadEvent event) {
        plugin.globalSensitiveBlocks.remove(new LongWrapper(ChunkPos.asLong(event.getChunk().getX(), event.getChunk().getZ())));
    }
}