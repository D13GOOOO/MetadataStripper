package com.angryguyy.metadatastripper;

import java.util.Map;
import java.util.Timer;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.bukkit.Bukkit;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.java.JavaPlugin;

import com.angryguyy.metadatastripper.data.LongWrapper;
import com.angryguyy.metadatastripper.data.PlayerData;
import com.angryguyy.metadatastripper.data.VectorialLocation;
import com.angryguyy.metadatastripper.listeners.NettyInjector;
import com.angryguyy.metadatastripper.listeners.PlayerListener;
import com.angryguyy.metadatastripper.listeners.WorldListener;
import com.angryguyy.metadatastripper.tasks.RayTraceTimerTask;
import com.angryguyy.metadatastripper.tasks.UpdateBukkitRunnable;
import com.google.common.util.concurrent.ThreadFactoryBuilder;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

/**
 * MetadataStripper - High-Performance Asynchronous Anti-Xray & Anti-ESP Engine.
 * <p>
 * This is the main plugin class. It initializes the zero-GC lookup tables,
 * thread pools, and Netty packet injectors. It is fully compatible with both
 * standard Spigot/Paper servers and highly concurrent Folia environments.
 */
public final class MetadataStripper extends JavaPlugin {

    private boolean folia = false;
    private volatile boolean running = false;
    private volatile boolean timingsEnabled = false;

    // Concurrent registries for active players and loaded chunk data
    private final ConcurrentMap<UUID, PlayerData> playerData = new ConcurrentHashMap<>();
    public final ConcurrentMap<LongWrapper, Map<BlockPos, Boolean>> globalSensitiveBlocks = new ConcurrentHashMap<>();

    /**
     * Ultra-fast O(1) Lookup Table to identify blocks that need to be obfuscated.
     * Dynamically sized at runtime to perfectly match the server's NMS block registry size.
     */
    public static final boolean[] sensitiveGlobal;

    static {
        int maxStates = 50000; // Fallback size
        try {
            // Dynamically fetch the exact size of the Minecraft block state registry
            maxStates = Block.BLOCK_STATE_REGISTRY.size() + 1000;
        } catch (Exception ignored) {}
        sensitiveGlobal = new boolean[maxStates];
    }

    private ExecutorService executorService;
    private Timer timer;
    private long updateTicks = 1L;
    private NettyInjector nettyInjector;

    @Override
    public void onEnable() {
        // Detect Folia architecture for region-based multithreading compatibility
        try {
            Class.forName("io.papermc.paper.threadedregions.RegionizedServer");
            folia = true;
        } catch (ClassNotFoundException ignored) {}

        // Populate the O(1) Lookup Table with sensitive blocks
        setupSensitiveBlocks();

        running = true;

        // Initialize the asynchronous Ray-Tracing thread pool based on available CPU cores
        executorService = Executors.newFixedThreadPool(
                Math.max(Runtime.getRuntime().availableProcessors(), 1),
                new ThreadFactoryBuilder()
                        .setThreadFactory(Executors.defaultThreadFactory())
                        .setNameFormat("MetadataStripper RayTrace thread %d")
                        .setDaemon(true)
                        .build()
        );

        // Start the master metronome (dispatches ray-trace calculations every 50ms)
        timer = new Timer("MetadataStripper RayTrace Timer", true);
        timer.schedule(new RayTraceTimerTask(this), 0L, 50L);
        updateTicks = 1L;

        // If not Folia, schedule the global packet sender task on the main thread
        if (!folia) {
            new UpdateBukkitRunnable(this).runTaskTimer(this, 0L, updateTicks);
        }

        // Register event listeners
        PluginManager pluginManager = getServer().getPluginManager();
        pluginManager.registerEvents(new WorldListener(this), this);
        pluginManager.registerEvents(new PlayerListener(this), this);

        // Inject the Netty pipeline interceptor into all currently online players
        nettyInjector = new NettyInjector(this);
        pluginManager.registerEvents(nettyInjector, this);
        Bukkit.getOnlinePlayers().forEach(nettyInjector::injectPlayer);

        getLogger().info("TOTAL Anti-Xray Engine enabled! Air-exposed blocks are now dynamically masked.");
    }

    /**
     * Pre-calculates and caches the internal NMS IDs of all sensitive blocks.
     * This eliminates the need for expensive Material checks during real-time chunk loading.
     */
    private void setupSensitiveBlocks() {
        for (int i = 0; i < Block.BLOCK_STATE_REGISTRY.size(); i++) {
            try {
                BlockState state = Block.stateById(i);
                if (state != null) {
                    org.bukkit.Material mat = state.createCraftBlockData().getMaterial();

                    switch (mat) {
                        // Ores
                        case DIAMOND_ORE: case DEEPSLATE_DIAMOND_ORE:
                        case IRON_ORE: case DEEPSLATE_IRON_ORE:
                        case GOLD_ORE: case DEEPSLATE_GOLD_ORE:
                        case COPPER_ORE: case DEEPSLATE_COPPER_ORE:
                        case COAL_ORE: case DEEPSLATE_COAL_ORE:
                        case EMERALD_ORE: case DEEPSLATE_EMERALD_ORE:
                        case LAPIS_ORE: case DEEPSLATE_LAPIS_ORE:
                        case REDSTONE_ORE: case DEEPSLATE_REDSTONE_ORE:
                        case NETHER_QUARTZ_ORE: case NETHER_GOLD_ORE:
                        case ANCIENT_DEBRIS:

                            // Raw Blocks & Valuables
                        case RAW_IRON_BLOCK: case RAW_GOLD_BLOCK: case RAW_COPPER_BLOCK:
                        case DIAMOND_BLOCK: case IRON_BLOCK: case GOLD_BLOCK: case EMERALD_BLOCK:
                        case LAPIS_BLOCK: case REDSTONE_BLOCK: case COAL_BLOCK: case NETHERITE_BLOCK:

                            // Storage & Utility
                        case CHEST: case TRAPPED_CHEST: case ENDER_CHEST: case BARREL:
                        case HOPPER: case DROPPER: case DISPENSER:
                        case FURNACE: case BLAST_FURNACE: case SMOKER:
                        case BREWING_STAND: case DECORATED_POT: case CHISELED_BOOKSHELF:

                            // Shulker Boxes
                        case SHULKER_BOX: case WHITE_SHULKER_BOX: case ORANGE_SHULKER_BOX:
                        case MAGENTA_SHULKER_BOX: case LIGHT_BLUE_SHULKER_BOX: case YELLOW_SHULKER_BOX:
                        case LIME_SHULKER_BOX: case PINK_SHULKER_BOX: case GRAY_SHULKER_BOX:
                        case LIGHT_GRAY_SHULKER_BOX: case CYAN_SHULKER_BOX: case PURPLE_SHULKER_BOX:
                        case BLUE_SHULKER_BOX: case BROWN_SHULKER_BOX: case GREEN_SHULKER_BOX:
                        case RED_SHULKER_BOX: case BLACK_SHULKER_BOX:

                            // Dungeon & Spawners
                        case SPAWNER: case TRIAL_SPAWNER: case VAULT:

                            // Geodes
                        case AMETHYST_CLUSTER: case BUDDING_AMETHYST:
                        case LARGE_AMETHYST_BUD: case MEDIUM_AMETHYST_BUD: case SMALL_AMETHYST_BUD:

                            // Sculk & Deep Dark
                        case SCULK_SENSOR: case CALIBRATED_SCULK_SENSOR: case SCULK_SHRIEKER: case SCULK_CATALYST:

                            // Structures & Portals
                        case MOSSY_COBBLESTONE: case MOSSY_STONE_BRICKS: case CRACKED_STONE_BRICKS:
                        case OBSIDIAN: case CRYING_OBSIDIAN:
                        case LODESTONE: case END_PORTAL_FRAME:
                        case SUSPICIOUS_SAND: case SUSPICIOUS_GRAVEL:

                            // Infested Blocks (Silverfish)
                        case INFESTED_STONE: case INFESTED_COBBLESTONE: case INFESTED_STONE_BRICKS:
                        case INFESTED_MOSSY_STONE_BRICKS: case INFESTED_CRACKED_STONE_BRICKS:
                        case INFESTED_CHISELED_STONE_BRICKS: case INFESTED_DEEPSLATE:

                            sensitiveGlobal[i] = true;
                            break;

                        default:
                            break;
                    }
                }
            } catch (Exception ignored) {}
        }
    }

    @Override
    public void onDisable() {
        running = false;

        if (timer != null) {
            timer.cancel();
        }

        // Graceful shutdown of the asynchronous thread pool
        if (executorService != null) {
            executorService.shutdownNow();
            try {
                executorService.awaitTermination(1000L, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        playerData.clear();
        globalSensitiveBlocks.clear();

        // Remove packet interceptors from online players to prevent post-disable console spam
        if (nettyInjector != null) {
            Bukkit.getOnlinePlayers().forEach(nettyInjector::removePlayer);
        }

        getLogger().info("MetadataStripper successfully disabled and memory cleared.");
    }

    public boolean isFolia() {
        return folia;
    }

    public boolean isRunning() {
        return running;
    }

    public boolean isTimingsEnabled() {
        return timingsEnabled;
    }

    public ConcurrentMap<UUID, PlayerData> getPlayerData() {
        return playerData;
    }

    public ExecutorService getExecutorService() {
        return executorService;
    }

    public long getUpdateTicks() {
        return updateTicks;
    }

    /**
     * Validates if a player is legitimate (e.g., ignores Citizens NPCs).
     */
    public boolean validatePlayer(Player player) {
        return !player.hasMetadata("NPC");
    }

    /**
     * Validates if the player's async data is fully initialized and active.
     */
    @SuppressWarnings("unused")
    public boolean validatePlayerData(Player player, PlayerData playerData, String methodName) {
        if (playerData == null) return validatePlayer(player);
        return true;
    }

    /**
     * Wraps the player's vectorial location into a thread-safe array.
     */
    public static VectorialLocation[] getLocations(Entity entity, VectorialLocation location) {
        return new VectorialLocation[] { location };
    }
}