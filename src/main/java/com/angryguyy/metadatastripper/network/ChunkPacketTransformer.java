package com.angryguyy.metadatastripper.network;

import com.angryguyy.metadatastripper.MetadataStripper;
import com.angryguyy.metadatastripper.cache.BlockStateCache;
import com.angryguyy.metadatastripper.util.ObfuscationPalette;
import com.angryguyy.metadatastripper.util.ReflectionAccess;

import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.game.*;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import org.bukkit.World.Environment;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.entity.Player;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.invoke.VarHandle;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.UUID;

/**
 * State-of-the-art engine for NMS chunk cloning and packet obfuscation.
 * <p>
 * This class serves as the core transformation engine, safely intercepting and modifying outbound
 * spatial data (Chunks, Section Block Updates, and Single Block Updates) to neutralize X-Ray mods,
 * Cave ESPs, and Stash Finders.
 * <p>
 * <b>Architectural Notes:</b>
 * <ul>
 *   <li><b>Hybrid Memory Model:</b> High-frequency reads utilize Java 21's ultra-fast {@link VarHandle} API,
 *       while structural writes to immutable {@code final} NMS fields utilize standard Reflection to gracefully
 *       bypass access restrictions without relying on deprecated APIs.</li>
 *   <li><b>Dynamic Linking:</b> Utilizes {@link MethodHandle} to securely invoke Mojang's internal
 *       chunk serialization constructors, bypassing compile-time deprecation warnings while allowing
 *       the JIT compiler to inline allocations for zero performance penalty.</li>
 *   <li><b>Zero-GC Ephemeral Cloning:</b> NMS {@link LevelChunk} objects are shared globally by the server.
 *       Mutating them directly would corrupt the world for all players. Instead, this engine allocates
 *       "ghost" chunks bypassing object constructors, deep-copies only necessary {@code PalettedContainer}s,
 *       and reconstructs ephemeral packets tailored exclusively for the recipient client.</li>
 *   <li><b>Thread Safety:</b> Designed to be executed asynchronously on Folia's Region Schedulers.
 *       It reads from active server world states but writes only to isolated, unreferenced memory buffers.</li>
 * </ul>
 */
public final class ChunkPacketTransformer {

    /** Reflected field for overriding the immutable {@code chunkData} reference inside chunk packets. */
    private static Field chunkDataField;

    /** Reflected field for overriding the immutable {@code sections} array inside chunk access structures. */
    private static Field chunkAccessSectionsField;

    /** Reflected field for modifying block states within section multi-block update packets. */
    private static Field sectionBlocksUpdateStatesField;

    /** High-performance handle to read block entity list payloads from chunk data. */
    private static VarHandle blockEntitiesDataHandle;

    /** High-performance handle to read packed XZ coordinates from block entity info structures. */
    private static VarHandle blockEntityPackedXZHandle;

    /** High-performance handle to read vertical Y coordinates from block entity info structures. */
    private static VarHandle blockEntityYHandle;

    /** High-performance handle to read block entity types. */
    private static VarHandle blockEntityTypeHandle;

    /** High-performance handle to read section positions from section block update packets. */
    private static VarHandle sectionBlocksUpdateSectionPosHandle;

    /** JIT-optimized method handle to bypass deprecation warnings on the chunk packet data constructor. */
    private static MethodHandle chunkDataConstructor;

    /** NMS Method handle to safely clone block data palettes without triggering lighting recalculations. */
    private static Method palettedContainerCopy;

    /** Pre-computed array of non-static fields for rapid LevelChunkSection cloning. */
    private static final List<Field> SECTION_FIELDS = new ArrayList<>();

    /** Pre-computed array of non-static fields for rapid LevelChunk cloning. */
    private static final List<Field> CHUNK_FIELDS = new ArrayList<>();

    /** Pre-computed array of non-static fields for section block update packets. */
    private static final List<Field> SECTION_UPDATE_FIELDS = new ArrayList<>();

    /** Holds any fatal exception encountered during the initial reflection mapping phase. */
    public static Throwable initializationFailure;

    static {
        try {
            chunkDataField = resolveField(ClientboundLevelChunkWithLightPacket.class, "chunkData", ClientboundLevelChunkPacketData.class);
            chunkAccessSectionsField = resolveField(net.minecraft.world.level.chunk.ChunkAccess.class, "sections", LevelChunkSection[].class);

            Field bedField = resolveField(ClientboundLevelChunkPacketData.class, "blockEntitiesData", List.class);
            blockEntitiesDataHandle = ReflectionAccess.getVarHandle(bedField);

            Class<?> beInfoClass = Class.forName("net.minecraft.network.protocol.game.ClientboundLevelChunkPacketData$BlockEntityInfo");
            blockEntityPackedXZHandle = ReflectionAccess.getVarHandle(beInfoClass.getDeclaredField("packedXZ"));
            blockEntityYHandle = ReflectionAccess.getVarHandle(beInfoClass.getDeclaredField("y"));
            blockEntityTypeHandle = ReflectionAccess.getVarHandle(beInfoClass.getDeclaredField("type"));

            chunkDataConstructor = MethodHandles.lookup().findConstructor(
                    ClientboundLevelChunkPacketData.class,
                    MethodType.methodType(void.class, LevelChunk.class)
            );

            palettedContainerCopy = net.minecraft.world.level.chunk.PalettedContainer.class.getMethod("copy");
            palettedContainerCopy.setAccessible(true);

            sectionBlocksUpdateStatesField = resolveField(ClientboundSectionBlocksUpdatePacket.class, "states", BlockState[].class);
            Field sectionPosField = resolveField(ClientboundSectionBlocksUpdatePacket.class, "sectionPos", net.minecraft.core.SectionPos.class);
            sectionBlocksUpdateSectionPosHandle = ReflectionAccess.getVarHandle(sectionPosField);

            for (Field f : LevelChunkSection.class.getDeclaredFields()) {
                if (!Modifier.isStatic(f.getModifiers())) {
                    f.setAccessible(true);
                    SECTION_FIELDS.add(f);
                }
            }

            Class<?> clazz = LevelChunk.class;
            while (clazz != null && clazz != Object.class) {
                for (Field f : clazz.getDeclaredFields()) {
                    if (!Modifier.isStatic(f.getModifiers())) {
                        f.setAccessible(true);
                        CHUNK_FIELDS.add(f);
                    }
                }
                clazz = clazz.getSuperclass();
            }

            Class<?> secClazz = ClientboundSectionBlocksUpdatePacket.class;
            while (secClazz != null && secClazz != Object.class) {
                for (Field f : secClazz.getDeclaredFields()) {
                    if (!Modifier.isStatic(f.getModifiers())) {
                        f.setAccessible(true);
                        SECTION_UPDATE_FIELDS.add(f);
                    }
                }
                secClazz = secClazz.getSuperclass();
            }

        } catch (Throwable throwable) {
            initializationFailure = throwable;
        }
    }

    /**
     * Private constructor to prevent instantiation of this static utility engine.
     */
    private ChunkPacketTransformer() {
        throw new UnsupportedOperationException("Utility class");
    }

    /**
     * Attempts to resolve an NMS field by explicit name, falling back to a unique type-based search if obfuscation mappings change.
     *
     * @param owner the target class to inspect
     * @param name  the expected field name
     * @param type  the expected field type
     * @return the resolved and accessible {@link Field}
     * @throws NoSuchFieldException if the field cannot be securely identified anywhere in the hierarchy
     */
    private static Field resolveField(Class<?> owner, String name, Class<?> type) throws NoSuchFieldException {
        try {
            return ReflectionAccess.findField(owner, name, type);
        } catch (NoSuchFieldException exception) {
            return ReflectionAccess.findUniqueField(owner, type);
        }
    }

    /**
     * Verifies if all necessary NMS memory offsets and handles were successfully mapped during startup.
     *
     * @return {@code true} if the transformer is fully operational; {@code false} if it requires a graceful fallback
     */
    public static boolean isReady() {
        return chunkDataField != null && chunkAccessSectionsField != null
                && blockEntitiesDataHandle != null && chunkDataConstructor != null
                && sectionBlocksUpdateStatesField != null;
    }

    /**
     * The core transformation pipeline for outbound spatial packets.
     * <p>
     * Evaluates single block updates, multi-block section updates, and massive 16x384x16 chunk structures.
     * Subterranean areas below the configured Y-threshold in aggressive mode undergo "Solid Fill" obfuscation,
     * replacing standard cave geometry with solid Stone or Deepslate to eliminate server lag and blind Cave ESPs.
     *
     * @param plugin         the active plugin instance
     * @param player         the recipient player
     * @param msg            the un-obfuscated outbound network packet
     * @param aggressiveYMax the vertical Y-level threshold for aggressive subterranean filling
     * @return a safely obfuscated packet ready for client serialization, or the original packet if no mutations were required
     */
    @SuppressWarnings("DuplicatedCode")
    public static Object transformRegionPacket(MetadataStripper plugin, Player player, Object msg, int aggressiveYMax) {
        BlockEntityFilter.updatePosition(player);

        if (msg instanceof ClientboundBlockUpdatePacket packet) {
            Level level = ((CraftPlayer) player).getHandle().level();
            BlockState original = packet.getBlockState();
            BlockState sanitized = BlockStateCache.sanitize(original);
            int engineMode = plugin.getEngineMode();
            int packetY = packet.getPos().getY();

            if (engineMode >= 2 && packetY < aggressiveYMax) {
                Environment env = getEnvironmentFast(level);
                BlockState solidFill = (packetY < 0) ? ObfuscationPalette.getObfuscatedBlock(engineMode, packet.getPos(), env) : Blocks.STONE.defaultBlockState();
                if (original.getBlock() != Blocks.BEDROCK || packetY > player.getWorld().getMinHeight()) {
                    sanitized = solidFill;
                }
            } else if (original.getBlock() == Blocks.BEDROCK && packetY > player.getWorld().getMinHeight()) {
                sanitized = ObfuscationPalette.getObfuscatedBlock(engineMode, packet.getPos(), getEnvironmentFast(level));
            }

            if (original != sanitized) {
                return new ClientboundBlockUpdatePacket(packet.getPos(), sanitized);
            }
            return msg;
        }

        if (msg instanceof ClientboundSectionBlocksUpdatePacket sectionPacket) {
            if (sectionBlocksUpdateStatesField == null) return msg;
            try {
                BlockState[] originalStates = (BlockState[]) sectionBlocksUpdateStatesField.get(sectionPacket);
                net.minecraft.core.SectionPos sectionPos = (net.minecraft.core.SectionPos) sectionBlocksUpdateSectionPosHandle.get(sectionPacket);

                int globalYBase = sectionPos.y() << 4;
                int engineMode = plugin.getEngineMode();
                boolean isAggressive = engineMode >= 2;
                boolean fullyUnderground = globalYBase + 15 < aggressiveYMax;

                BlockState[] newStates = originalStates.clone();
                boolean modified = false;

                Environment env = getEnvironmentFast(((CraftPlayer) player).getHandle().level());
                BlockState deepslate = ObfuscationPalette.getObfuscatedBlock(engineMode, BlockPos.ZERO, env);
                BlockState stone = Blocks.STONE.defaultBlockState();

                for (int i = 0; i < newStates.length; i++) {
                    BlockState state = newStates[i];
                    if (isAggressive && fullyUnderground) {
                        if (state.getBlock() != Blocks.BEDROCK) {
                            newStates[i] = (globalYBase < 0) ? deepslate : stone;
                            modified = true;
                        }
                    } else {
                        BlockState sanitized = BlockStateCache.sanitize(state);
                        if (sanitized != state) {
                            newStates[i] = sanitized;
                            modified = true;
                        }
                    }
                }

                if (modified) {
                    ClientboundSectionBlocksUpdatePacket fakePacket = ReflectionAccess.allocateInstance(ClientboundSectionBlocksUpdatePacket.class);
                    for (Field f : SECTION_UPDATE_FIELDS) {
                        f.set(fakePacket, f.get(sectionPacket));
                    }
                    sectionBlocksUpdateStatesField.set(fakePacket, newStates);
                    return fakePacket;
                }
            } catch (Throwable t) {
                return msg;
            }
            return msg;
        }

        if (msg instanceof ClientboundLevelChunkWithLightPacket chunkPacket) {
            if (!isReady()) {
                MetadataStripper.recordFallback(false);
                return msg;
            }

            net.minecraft.world.entity.player.Player nmsPlayer = ((CraftPlayer) player).getHandle();
            ServerLevel serverLevel = (ServerLevel) nmsPlayer.level();
            LevelChunk chunk = serverLevel.getChunkSource().getChunkNow(chunkPacket.getX(), chunkPacket.getZ());

            if (chunk == null) {
                MetadataStripper.recordFallback(false);
                return msg;
            }

            Environment env = getEnvironmentFast(serverLevel);
            int engineMode = plugin.getEngineMode();
            boolean isAggressiveMode = engineMode >= 2;

            BlockState stone = ObfuscationPalette.getObfuscatedBlock(engineMode, new BlockPos(0, 1, 0), env);
            BlockState deepslate = ObfuscationPalette.getObfuscatedBlock(engineMode, new BlockPos(0, -1, 0), env);
            int minBuildHeight = player.getWorld().getMinHeight();

            boolean[] sensitiveStates = MetadataStripper.sensitiveGlobal;

            try {
                LevelChunkSection[] originalSections = chunk.getSections();
                LevelChunkSection[] clonedSections = new LevelChunkSection[originalSections.length];
                boolean chunkModified = false;

                for (int i = 0; i < originalSections.length; i++) {
                    LevelChunkSection section = originalSections[i];
                    if (section == null || section.hasOnlyAir()) {
                        clonedSections[i] = section;
                        continue;
                    }

                    int sectionY = (minBuildHeight >> 4) + i;
                    int globalSectionY = sectionY << 4;
                    boolean isUnderground = globalSectionY < aggressiveYMax;

                    if (isUnderground && isAggressiveMode) {
                        LevelChunkSection fakeSection = ReflectionAccess.allocateInstance(LevelChunkSection.class);
                        for (Field f : SECTION_FIELDS) {
                            Object val = f.get(section);
                            if (val != null && val.getClass() == net.minecraft.world.level.chunk.PalettedContainer.class) {
                                val = palettedContainerCopy.invoke(val);
                            }
                            f.set(fakeSection, val);
                        }

                        BlockState solidFill = (globalSectionY < 0) ? deepslate : stone;
                        for (int y = 0; y < 16; y++) {
                            for (int x = 0; x < 16; x++) {
                                for (int z = 0; z < 16; z++) {
                                    fakeSection.setBlockState(x, y, z, solidFill);
                                }
                            }
                        }
                        clonedSections[i] = fakeSection;
                        chunkModified = true;
                        continue;
                    }

                    boolean sectionModified = false;

                    for (int y = 0; y < 16; y++) {
                        int actualY = globalSectionY + y;
                        for (int x = 0; x < 16; x++) {
                            for (int z = 0; z < 16; z++) {
                                BlockState state = section.getBlockState(x, y, z);
                                int id = Block.BLOCK_STATE_REGISTRY.getId(state);

                                boolean isSensitive = id >= 0 && id < sensitiveStates.length && sensitiveStates[id];

                                if (isSensitive || (state.getBlock() == Blocks.BEDROCK && actualY > minBuildHeight)) {
                                    sectionModified = true;
                                }
                            }
                        }
                    }

                    if (!sectionModified) {
                        clonedSections[i] = section;
                        continue;
                    }

                    LevelChunkSection fakeSection = ReflectionAccess.allocateInstance(LevelChunkSection.class);
                    for (Field f : SECTION_FIELDS) {
                        Object val = f.get(section);
                        if (val != null && val.getClass() == net.minecraft.world.level.chunk.PalettedContainer.class) {
                            val = palettedContainerCopy.invoke(val);
                        }
                        f.set(fakeSection, val);
                    }

                    for (int y = 0; y < 16; y++) {
                        int actualY = globalSectionY + y;
                        BlockState replacement = actualY < 0 ? deepslate : stone;

                        for (int x = 0; x < 16; x++) {
                            for (int z = 0; z < 16; z++) {
                                BlockState state = section.getBlockState(x, y, z);
                                int id = Block.BLOCK_STATE_REGISTRY.getId(state);

                                boolean isSensitive = id >= 0 && id < sensitiveStates.length && sensitiveStates[id];

                                if (isSensitive || (state.getBlock() == Blocks.BEDROCK && actualY > minBuildHeight)) {
                                    fakeSection.setBlockState(x, y, z, replacement);
                                }
                            }
                        }
                    }

                    clonedSections[i] = fakeSection;
                    chunkModified = true;
                }

                if (chunkModified) {
                    LevelChunk fakeChunk = ReflectionAccess.allocateInstance(LevelChunk.class);

                    for (Field f : CHUNK_FIELDS) {
                        f.set(fakeChunk, f.get(chunk));
                    }

                    chunkAccessSectionsField.set(fakeChunk, clonedSections);

                    ClientboundLevelChunkPacketData fakeData = (ClientboundLevelChunkPacketData) chunkDataConstructor.invoke(fakeChunk);
                    filterChunkBlockEntities(fakeData, chunkPacket.getX(), chunkPacket.getZ(), player.getUniqueId());

                    chunkDataField.set(chunkPacket, fakeData);
                } else {
                    filterChunkBlockEntities((ClientboundLevelChunkPacketData) chunkDataField.get(chunkPacket), chunkPacket.getX(), chunkPacket.getZ(), player.getUniqueId());
                }

            } catch (Throwable throwable) {
                MetadataStripper.recordFallback(false);
                plugin.getLogger().log(java.util.logging.Level.WARNING, "Unable to sanitize chunk packet for " + player.getName(), throwable);
                return msg;
            }

            return chunkPacket;
        }

        return msg;
    }

    /**
     * Intercepts and purges sensitive Block Entity (NBT) payloads embedded directly inside chunk packets.
     * <p>
     * Prevents advanced Stash Finders from detecting hidden containers (Chests, Shulkers, Vaults)
     * by scrubbing raw NBT tags before the client ever receives them. Iterates safely over internal data structures
     * using high-performance {@link VarHandle} references.
     *
     * @param chunkData  the NMS chunk serialization payload container
     * @param chunkX     the grid X coordinate of the chunk
     * @param chunkZ     the grid Z coordinate of the chunk
     * @param playerUuid the receiving player's unique identifier
     */
    private static void filterChunkBlockEntities(ClientboundLevelChunkPacketData chunkData, int chunkX, int chunkZ, UUID playerUuid) {
        Object value = blockEntitiesDataHandle.get(chunkData);
        if (value instanceof List<?> blockEntities) {
            Iterator<?> iterator = blockEntities.iterator();
            while (iterator.hasNext()) {
                Object blockEntity = iterator.next();
                int packedXZ = (int) blockEntityPackedXZHandle.get(blockEntity);
                int y = (int) blockEntityYHandle.get(blockEntity);

                BlockPos position = new BlockPos((chunkX << 4) + (packedXZ & 15), y, (chunkZ << 4) + ((packedXZ >> 4) & 15));

                if (BlockEntityFilter.shouldBlock(playerUuid, (net.minecraft.world.level.block.entity.BlockEntityType<?>) blockEntityTypeHandle.get(blockEntity), position)) {
                    iterator.remove();
                }
            }
        }

        chunkData.getExtraPackets().removeIf(extraPacket ->
                extraPacket instanceof ClientboundBlockEntityDataPacket blockEntityPacket
                        && BlockEntityFilter.shouldBlock(playerUuid, blockEntityPacket.getType(), blockEntityPacket.getPos()));
    }

    /**
     * Performs an ultra-fast evaluation of the current world dimension environment.
     * <p>
     * Bypasses the Bukkit API ({@code World.getEnvironment()}), which can introduce thread-safety
     * issues or object allocation overhead on heavily modified multi-threaded server software.
     *
     * @param level the native NMS Level instance
     * @return the corresponding Bukkit {@link Environment}
     */
    private static Environment getEnvironmentFast(Level level) {
        if (level.dimension() == Level.NETHER) return Environment.NETHER;
        if (level.dimension() == Level.END) return Environment.THE_END;
        return Environment.NORMAL;
    }
}