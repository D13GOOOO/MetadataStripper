package com.angryguyy.metadatastripper.tasks;

import java.util.Collection;
import java.util.Iterator;
import java.util.Map.Entry;
import java.util.Queue;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentMap;
import java.util.logging.Level;

import org.bukkit.World;
import org.bukkit.util.Vector;

import com.angryguyy.metadatastripper.MetadataStripper;
import com.angryguyy.metadatastripper.data.ChunkBlocks;
import com.angryguyy.metadatastripper.data.LongWrapper;
import com.angryguyy.metadatastripper.data.MutableLongWrapper;
import com.angryguyy.metadatastripper.data.PlayerData;
import com.angryguyy.metadatastripper.data.Result;
import com.angryguyy.metadatastripper.data.VectorialLocation;
import com.angryguyy.metadatastripper.util.BlockIterator;
import com.angryguyy.metadatastripper.util.BlockOcclusionCulling;
import com.angryguyy.metadatastripper.util.BlockOcclusionCulling.BlockOcclusionGetter;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;

@SuppressWarnings("all")
public final class RayTraceCallable implements Callable<Void> {
    private static final BlockState AIR = Blocks.AIR.defaultBlockState();

    private static final boolean[] solidGlobal;
    static {
        int maxStates = 100000;
        try { maxStates = Block.BLOCK_STATE_REGISTRY.size(); } catch (Exception ignored) {}

        solidGlobal = new boolean[maxStates];
        for (int i = 0; i < maxStates; i++) {
            try {
                BlockState state = Block.stateById(i);
                org.bukkit.Material mat = state.createCraftBlockData().getMaterial();
                solidGlobal[i] = mat.isOccluding() && mat != org.bukkit.Material.SPAWNER && mat != org.bukkit.Material.BARRIER && mat != org.bukkit.Material.SLIME_BLOCK;
            } catch (Exception e) {
                solidGlobal[i] = false;
            }
        }
    }

    private final MetadataStripper plugin;
    private final PlayerData playerData;
    private final CachedSectionBlockOcclusionGetter cachedSectionBlockOcclusionGetter;
    private final BlockOcclusionCulling blockOcclusionCulling;
    private final Collection<ChunkBlocks> chunks;
    private final double rayTraceDistance;
    private final double rayTraceDistanceSquared;
    private final boolean rehideBlocks;
    private final double rehideDistanceSquared;

    public RayTraceCallable(MetadataStripper plugin, PlayerData playerData) {
        this.plugin = plugin;
        this.playerData = playerData;
        MutableLongWrapper mutableLongWrapper = new MutableLongWrapper(0L);
        ConcurrentMap<LongWrapper, ChunkBlocks> chunks = playerData.getChunks();

        // 100% Bukkit API Sicura per calcolare le sezioni, zero crash NMS!
        World bukkitWorld = playerData.getLocations()[0].getWorld();
        final int worldMinSy = bukkitWorld.getMinHeight() >> 4;
        final int worldMaxSy = bukkitWorld.getMaxHeight() >> 4;

        cachedSectionBlockOcclusionGetter = new CachedSectionBlockOcclusionGetter() {
            private static final boolean UNLOADED_OCCLUDING = true;
            private LevelChunk chunk;
            private LevelChunkSection section;
            private int chunkX;
            private int sectionY;
            private int chunkZ;

            @Override
            public boolean isOccluding(int x, int y, int z) {
                return checkOcclusion(x, y, z, false);
            }

            @Override
            public boolean isOccludingRay(int x, int y, int z) {
                return checkOcclusion(x, y, z, true);
            }

            private boolean checkOcclusion(int x, int y, int z, boolean updateCache) {
                int cx = x >> 4;
                int sy = y >> 4;
                int cz = z >> 4;

                if (this.chunkX != cx || this.chunkZ != cz) {
                    if (updateCache) { this.chunkX = cx; this.chunkZ = cz; this.sectionY = sy; }
                    mutableLongWrapper.setValue(ChunkPos.asLong(cx, cz));
                    ChunkBlocks chunkBlocks = chunks.get(mutableLongWrapper);

                    if (chunkBlocks == null) {
                        if (updateCache) { chunk = null; section = null; }
                        return UNLOADED_OCCLUDING;
                    }
                    LevelChunk localChunk = chunkBlocks.getChunk();
                    if (localChunk == null) {
                        if (updateCache) { chunk = null; section = null; }
                        return UNLOADED_OCCLUDING;
                    }
                    if (updateCache) chunk = localChunk;

                    if (sy < worldMinSy || sy >= worldMaxSy) {
                        if (updateCache) section = null;
                        return false;
                    }
                    LevelChunkSection[] sections = localChunk.getSections();
                    int sectionIndex = sy - worldMinSy;
                    if (sectionIndex < 0 || sectionIndex >= sections.length) {
                        if (updateCache) section = null;
                        return false;
                    }
                    LevelChunkSection localSection = sections[sectionIndex];
                    if (updateCache) section = localSection;

                    if (localSection == null || localSection.hasOnlyAir()) return false;
                    return solidGlobal[Block.getId(getBlockState(localSection, x, y, z))];
                }

                if (this.sectionY != sy) {
                    if (updateCache) this.sectionY = sy;
                    if (chunk == null) return UNLOADED_OCCLUDING;
                    if (sy < worldMinSy || sy >= worldMaxSy) {
                        if (updateCache) section = null;
                        return false;
                    }
                    LevelChunkSection[] sections = chunk.getSections();
                    int sectionIndex = sy - worldMinSy;
                    if (sectionIndex < 0 || sectionIndex >= sections.length) {
                        if (updateCache) section = null;
                        return false;
                    }
                    LevelChunkSection localSection = sections[sectionIndex];
                    if (updateCache) section = localSection;

                    if (localSection == null || localSection.hasOnlyAir()) return false;
                    return solidGlobal[Block.getId(getBlockState(localSection, x, y, z))];
                }

                if (section == null) return chunk == null && UNLOADED_OCCLUDING;
                return solidGlobal[Block.getId(getBlockState(section, x, y, z))];
            }

            @Override
            public void initializeCache(LevelChunk chunk, int chunkX, int sectionY, int chunkZ) {
                this.chunk = chunk;
                int sectionIndex = sectionY - worldMinSy;
                LevelChunkSection[] sections = chunk.getSections();
                if (sectionIndex >= 0 && sectionIndex < sections.length) {
                    section = sections[sectionIndex];
                } else {
                    section = null;
                }
                this.chunkX = chunkX; this.sectionY = sectionY; this.chunkZ = chunkZ;
            }

            @Override
            public void clearCache() { chunk = null; section = null; }
        };

        blockOcclusionCulling = new BlockOcclusionCulling(
                (ix, iy, iz, startX, startY, startZ, dirX, dirY, dirZ, dist) ->
                        new BlockIterator(ix, iy, iz, startX, startY, startZ, dirX, dirY, dirZ, dist, true),
                cachedSectionBlockOcclusionGetter,
                true
        );

        this.chunks = chunks.values();
        rayTraceDistance = 64.0;
        rayTraceDistanceSquared = rayTraceDistance * rayTraceDistance;
        rehideBlocks = true;
        rehideDistanceSquared = 66.0 * 66.0;
    }

    @Override
    public Void call() {
        try { rayTrace(); } catch (Throwable t) {
            plugin.getLogger().log(Level.SEVERE, "An error occured on the RayTrace thread", t);
        }
        return null;
    }

    private void rayTrace() {
        if (blockOcclusionCulling == null) { return; }

        ConcurrentMap<LongWrapper, ChunkBlocks> chunks = playerData.getChunks();
        VectorialLocation[] locations = playerData.getLocations();
        Vector playerVector = locations[0].getVector();
        double playerX = playerVector.getX(); double playerY = playerVector.getY(); double playerZ = playerVector.getZ();

        playerVector.setX(playerX - rayTraceDistance); playerVector.setZ(playerZ - rayTraceDistance);
        int chunkXMin = playerVector.getBlockX() >> 4; int chunkZMin = playerVector.getBlockZ() >> 4;
        playerVector.setX(playerX + rayTraceDistance); playerVector.setZ(playerZ + rayTraceDistance);
        int chunkXMax = playerVector.getBlockX() >> 4; int chunkZMax = playerVector.getBlockZ() >> 4;
        playerVector.setX(playerX); playerVector.setZ(playerZ);

        Queue<Result> results = playerData.getResults();

        for (ChunkBlocks chunkBlocks : this.chunks) {
            LevelChunk chunk = chunkBlocks.getChunk();
            if (chunk == null) { chunks.remove(chunkBlocks.getKey(), chunkBlocks); continue; }

            ChunkPos chunkPos = chunk.getPos();
            int chunkX = chunkPos.x; if (chunkX < chunkXMin || chunkX > chunkXMax) continue;
            int chunkZ = chunkPos.z; if (chunkZ < chunkZMin || chunkZ > chunkZMax) continue;

            Iterator<Entry<BlockPos, Boolean>> iterator = chunkBlocks.getBlocks().entrySet().iterator();

            while (iterator.hasNext()) {
                Entry<BlockPos, Boolean> blockHidden = iterator.next();
                BlockPos block = blockHidden.getKey();
                int x = block.getX(); int y = block.getY(); int z = block.getZ();
                double centerX = x + 0.5; double centerY = y + 0.5; double centerZ = z + 0.5;
                double differenceX = playerX - centerX; double differenceY = playerY - centerY; double differenceZ = playerZ - centerZ;
                double distanceSquared = differenceX * differenceX + differenceY * differenceY + differenceZ * differenceZ;

                if (!(distanceSquared <= rayTraceDistanceSquared)) continue;

                boolean visible = false;

                if (distanceSquared < rehideDistanceSquared) {
                    int sectionY = y >> 4;
                    for (int i = 0; i < locations.length; i++) {
                        VectorialLocation location = locations[i];
                        Vector direction = location.getDirection();
                        cachedSectionBlockOcclusionGetter.initializeCache(chunk, chunkX, sectionY, chunkZ);

                        if (i == 0) {
                            if (blockOcclusionCulling.isVisible(x, y, z, centerX, centerY, centerZ, differenceX, differenceY, differenceZ, distanceSquared, direction.getX(), direction.getY(), direction.getZ())) {
                                visible = true; break;
                            }
                        }
                    }
                }

                boolean hidden = blockHidden.getValue();

                if (visible) {
                    if (hidden) {
                        results.add(new Result(chunkBlocks, block, true));
                        if (rehideBlocks) { blockHidden.setValue(false); } else { iterator.remove(); }
                    }
                } else if (!hidden) {
                    results.add(new Result(chunkBlocks, block, false));
                    blockHidden.setValue(true);
                }
            }
        }
        cachedSectionBlockOcclusionGetter.clearCache();
    }

    private static BlockState getBlockState(LevelChunkSection section, int x, int y, int z) {
        try { return section.getBlockState(x & 15, y & 15, z & 15); }
        catch (Exception e) { return AIR; }
    }

    private interface CachedSectionBlockOcclusionGetter extends BlockOcclusionGetter {
        void initializeCache(LevelChunk chunk, int chunkX, int sectionY, int chunkZ);
        void clearCache();
    }
}