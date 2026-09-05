package com.angryguyy.metadatastripper.listeners;

import java.util.HashMap;
import java.util.Map;

import org.bukkit.craftbukkit.CraftWorld;
import org.bukkit.event.EventHandler;
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

public final class WorldListener implements Listener {
    private final MetadataStripper plugin;

    public WorldListener(MetadataStripper plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onChunkLoad(ChunkLoadEvent event) {
        int cx = event.getChunk().getX();
        int cz = event.getChunk().getZ();
        ServerLevel serverLevel = ((CraftWorld) event.getWorld()).getHandle();
        LevelChunk chunk = serverLevel.getChunkIfLoaded(cx, cz);
        if (chunk == null) return;

        Map<BlockPos, Boolean> sensitiveBlocks = new HashMap<>();

        // FIX: Usiamo l'API Bukkit per l'altezza minima, evitando crash NMS
        int minSy = event.getWorld().getMinHeight() >> 4;

        LevelChunkSection[] sections = chunk.getSections();

        for (int i = 0; i < sections.length; i++) {
            LevelChunkSection section = sections[i];
            if (section == null || section.hasOnlyAir()) continue;

            int startY = (minSy + i) * 16;

            for (int x = 0; x < 16; x++) {
                for (int y = 0; y < 16; y++) {
                    for (int z = 0; z < 16; z++) {
                        BlockState state = section.getBlockState(x, y, z);
                        try {
                            if (MetadataStripper.sensitiveGlobal[Block.getId(state)]) {
                                // Mettiamo 'true' così il ray-tracer sa che sono oscurati sul client
                                sensitiveBlocks.put(new BlockPos(cx * 16 + x, startY + y, cz * 16 + z), true);
                            }
                        } catch (Exception ignored) {}
                    }
                }
            }
        }

        if (!sensitiveBlocks.isEmpty()) {
            plugin.globalSensitiveBlocks.put(new LongWrapper(ChunkPos.asLong(cx, cz)), sensitiveBlocks);
        }
    }

    @EventHandler
    public void onChunkUnload(ChunkUnloadEvent event) {
        plugin.globalSensitiveBlocks.remove(new LongWrapper(ChunkPos.asLong(event.getChunk().getX(), event.getChunk().getZ())));
    }
}