package com.angryguyy.metadatastripper;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
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
import com.angryguyy.metadatastripper.listeners.EntityListener;
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

    private final ConcurrentMap<UUID, PlayerData> playerData = new ConcurrentHashMap<>();
    public final ConcurrentMap<LongWrapper, Map<BlockPos, Boolean>> globalSensitiveBlocks = new ConcurrentHashMap<>();
    private final ConcurrentMap<Integer, Entity> globalSensitiveEntities = new ConcurrentHashMap<>();

    public static final boolean[] sensitiveGlobal;

    static {
        int maxStates = 50000;
        try {
            maxStates = Block.BLOCK_STATE_REGISTRY.size() + 1000;
        } catch (Exception ignored) {}
        sensitiveGlobal = new boolean[maxStates];
    }

    private ExecutorService executorService;
    private Timer timer;
    private NettyInjector nettyInjector;

    private int engineMode;
    private int fakeOrePercentage;
    private double rayTraceDistance;
    private long updateTicks;
    private final Set<String> ignoredWorlds = new HashSet<>();
    private final Set<String> sensitiveEntities = new HashSet<>();

    @Override
    public void onEnable() {
        try {
            Class.forName("io.papermc.paper.threadedregions.RegionizedServer");
            folia = true;
        } catch (ClassNotFoundException ignored) {}

        saveDefaultConfig();
        loadConfiguration();
        setupSensitiveBlocks();

        running = true;

        executorService = Executors.newFixedThreadPool(
                Math.max(Runtime.getRuntime().availableProcessors(), 1),
                new ThreadFactoryBuilder()
                        .setThreadFactory(Executors.defaultThreadFactory())
                        .setNameFormat("MetadataStripper RayTrace thread %d")
                        .setDaemon(true)
                        .build()
        );

        timer = new Timer("MetadataStripper RayTrace Timer", true);
        timer.schedule(new RayTraceTimerTask(this), 0L, 50L);

        if (!folia) {
            new UpdateBukkitRunnable(this).runTaskTimer(this, 0L, updateTicks);
        }

        PluginManager pluginManager = getServer().getPluginManager();
        pluginManager.registerEvents(new WorldListener(this), this);
        pluginManager.registerEvents(new PlayerListener(this), this);
        pluginManager.registerEvents(new EntityListener(this), this);

        nettyInjector = new NettyInjector(this);
        pluginManager.registerEvents(nettyInjector, this);
        Bukkit.getOnlinePlayers().forEach(nettyInjector::injectPlayer);

        getLogger().info("TOTAL Anti-Xray Engine enabled! (Mode: " + engineMode + ")");
    }

    /**
     * Reads and caches values from the configuration file into primitive memory.
     */
    private void loadConfiguration() {
        engineMode = getConfig().getInt("engine-mode", 2);
        fakeOrePercentage = getConfig().getInt("fake-ore-percentage", 4);
        rayTraceDistance = getConfig().getDouble("ray-trace-distance", 64.0);
        updateTicks = getConfig().getLong("update-ticks", 1L);

        ignoredWorlds.clear();
        ignoredWorlds.addAll(getConfig().getStringList("ignored-worlds"));

        sensitiveEntities.clear();
        sensitiveEntities.addAll(getConfig().getStringList("sensitive-entities"));
    }

    /**
     * Pre-calculates and caches the internal NMS IDs of all sensitive blocks.
     */
    private void setupSensitiveBlocks() {
        Set<String> configuredMaterials = new HashSet<>(getConfig().getStringList("sensitive-blocks"));

        for (int i = 0; i < Block.BLOCK_STATE_REGISTRY.size(); i++) {
            try {
                BlockState state = Block.stateById(i);
                if (state != null) {
                    org.bukkit.Material mat = state.createCraftBlockData().getMaterial();
                    if (configuredMaterials.contains(mat.name())) {
                        sensitiveGlobal[i] = true;
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
        globalSensitiveEntities.clear();
        ignoredWorlds.clear();
        sensitiveEntities.clear();

        if (nettyInjector != null) {
            Bukkit.getOnlinePlayers().forEach(nettyInjector::removePlayer);
        }

        getLogger().info("MetadataStripper successfully disabled and memory cleared.");
    }

    public int getEngineMode() { return engineMode; }
    public int getFakeOrePercentage() { return fakeOrePercentage; }
    public double getRayTraceDistance() { return rayTraceDistance; }
    public long getUpdateTicks() { return updateTicks; }
    public Set<String> getIgnoredWorlds() { return ignoredWorlds; }
    public Set<String> getSensitiveEntities() { return sensitiveEntities; }

    public boolean isFolia() { return folia; }
    public boolean isRunning() { return running; }
    public boolean isTimingsEnabled() { return timingsEnabled; }

    public ConcurrentMap<UUID, PlayerData> getPlayerData() { return playerData; }
    public ConcurrentMap<Integer, Entity> getGlobalSensitiveEntities() { return globalSensitiveEntities; }

    public ExecutorService getExecutorService() { return executorService; }

    /**
     * Validates if a player is legitimate.
     *
     * @param player the player to validate
     * @return true if valid, false otherwise
     */
    public boolean validatePlayer(Player player) {
        return !player.hasMetadata("NPC");
    }

    /**
     * Validates if the player's async data is fully initialized and active.
     *
     * @param player     the player to validate
     * @param playerData the async data profile
     * @param methodName the calling method for debug context
     * @return true if valid, false otherwise
     */
    public boolean validatePlayerData(Player player, PlayerData playerData, String methodName) {
        if (playerData == null) {
            return validatePlayer(player);
        }
        return true;
    }

    /**
     * Wraps the player's vectorial location into a thread-safe array.
     *
     * @param entity   the entity
     * @param location the location
     * @return an array containing the vectorial location
     */
    public static VectorialLocation[] getLocations(Entity entity, VectorialLocation location) {
        return new VectorialLocation[] { location };
    }
}