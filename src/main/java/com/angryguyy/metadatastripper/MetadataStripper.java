package com.angryguyy.metadatastripper;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import org.bukkit.Bukkit;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.java.JavaPlugin;

import com.angryguyy.metadatastripper.commands.AdminCommand;
import com.angryguyy.metadatastripper.config.ConfigurationValidator;
import com.angryguyy.metadatastripper.engine.EntityCullingEngine;
import com.angryguyy.metadatastripper.listeners.BlockUpdateListener;
import com.angryguyy.metadatastripper.listeners.NettyInjector;
import com.angryguyy.metadatastripper.listeners.PlayerQuitListener;
import com.angryguyy.metadatastripper.listeners.ProximityRevealer;
import com.angryguyy.metadatastripper.network.BlockEntityFilter;
import com.angryguyy.metadatastripper.util.LicenseManager;
import com.angryguyy.metadatastripper.util.RegionSchedulerAdapter;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Core bootstrap and lifecycle management class for the MetadataStripper Engine.
 * <p>
 * This main plugin class acts as the central orchestrator for the entire protection suite.
 * It initializes lock-free lookup snapshots, mounts the Netty pipeline interceptors, registers
 * Bukkit listeners, and coordinates asynchronous tasks across Folia's Region Schedulers and Paper's
 * Global Scheduler.
 * <p>
 * <b>Architectural Notes:</b>
 * <ul>
 *   <li><b>Telemetry & Thread Safety:</b> Since packet transformation happens concurrently across
 *       multiple Netty I/O threads and Region Schedulers, all global telemetry counters are strictly
 *       implemented as {@link AtomicLong} to guarantee lock-free, zero-GC incrementation.</li>
 *   <li><b>Memory Model:</b> Configuration states and lookup tables (like the sensitive block array)
 *       are marked as {@code volatile}. During a hot-reload, the engine calculates the new state
 *       in the background and atomically publishes the new reference, ensuring network threads
 *       never read a partially rebuilt configuration.</li>
 *   <li><b>Dynamic Degradation:</b> The engine continuously monitors server TPS. If performance degrades,
 *       it dynamically falls back to a less aggressive obfuscation heuristic to prevent server crashes.</li>
 * </ul>
 * <p>
 * <b>Algorithmic Complexity:</b>
 * <ul>
 *   <li><b>Initialization:</b> O(N) where N is the internal Mojang block registry size (executed once per startup/reload).</li>
 *   <li><b>Runtime State:</b> O(1) array and map lookups for the outbound network path.</li>
 * </ul>
 */
public final class MetadataStripper extends JavaPlugin {

    /**
     * Operational states representing the overall health of the regional packet pipeline.
     * Exposed through the administrative {@code /ms diagnose} command.
     */
    public enum EngineHealth {
        /** Plugin is actively initializing its NMS components and Netty integrations. */
        STARTING,
        /** All required integrations were initialized successfully and the pipeline is stable. */
        ACTIVE,
        /** The plugin is experiencing regional timeouts, backpressure drops, or transformation failures. Requires administrator attention. */
        DEGRADED,
        /** Plugin lifecycle has ended, Netty handlers are unmounted, and scheduled work is cancelled. */
        DISABLED
    }

    /**
     * Instantiates the core plugin lifecycle component.
     * Invoked automatically by the Bukkit/Paper PluginLoader via reflection.
     */
    public MetadataStripper() {
        super();
    }

    /**
     * A highly optimized, zero-allocation lookup table mapping native NMS block-state IDs to sensitivity flags.
     * <p>
     * <b>Thread Safety:</b> Marked as {@code volatile}. A complete replacement array is built during
     * startup and hot-reloads, then atomically published. This guarantees that concurrent Netty packet
     * readers never observe a partially rebuilt configuration or encounter locking overhead.
     */
    public static volatile boolean[] sensitiveGlobal;

    /** Stores any critical exception encountered while sizing the NMS block registry. */
    private static Throwable sensitiveArrayInitializationFailure;

    /** Global telemetry: Number of block entity (NBT) payloads strictly discarded by the network filter. */
    public static final AtomicLong interceptedNbtPackets = new AtomicLong(0);

    /** Global telemetry: Number of entity metadata or equipment packets completely discarded by the culling filter. */
    public static final AtomicLong interceptedEntityPackets = new AtomicLong(0);

    /** Global telemetry: Number of entity packets discarded specifically because the target was outside the tactical radius. */
    public static final AtomicLong culledEntities = new AtomicLong(0);

    /** Global telemetry: Number of chunk packets successfully rewritten and obfuscated by the regional transformer. */
    public static final AtomicLong transformedChunkPackets = new AtomicLong(0);

    /** Global telemetry: Number of chunk packets forcefully passed through without transformation to prevent server thread locks. */
    public static final AtomicLong fallbackChunkPackets = new AtomicLong(0);

    /** Global telemetry: Number of regional packet transformation deadlines exceeded (2-second boundary limit). */
    public static final AtomicLong packetTimeouts = new AtomicLong(0);

    /** Global telemetry: Number of unrecoverable internal errors during chunk packet transformations. */
    public static final AtomicLong packetErrors = new AtomicLong(0);

    /** Global telemetry: Number of packets explicitly dropped because a player's channel hit its regional 32-packet queue limit. */
    public static final AtomicLong backpressureDrops = new AtomicLong(0);

    /** The volatile state tracking the runtime health of the plugin's asynchronous pipeline. */
    private static volatile EngineHealth engineHealth = EngineHealth.STARTING;

    static {
        int maxStates = 50000;
        try {
            maxStates = Block.BLOCK_STATE_REGISTRY.size() + 1000;
        } catch (Exception exception) {
            sensitiveArrayInitializationFailure = exception;
        }
        sensitiveGlobal = new boolean[maxStates];
    }

    private NettyInjector nettyInjector;
    private volatile int baseEngineMode;
    private volatile int alertThreshold;
    private ProximityRevealer proximityRevealer;

    /** A reference to the global profiler task, allowing safe cancellation during shutdown or reloads. */
    private Runnable profilerCancellation = () -> { };

    /** A reference to the global garbage collection task to prevent memory leaks from orphaned UUIDs. */
    private Runnable gcCancellation = () -> { };

    /**
     * Executes the primary initialization phase of the engine.
     * <p>
     * <b>Lifecycle Steps:</b>
     * <ol>
     *   <li>Pre-loads critical NMS caches to avoid latency spikes upon first use.</li>
     *   <li>Validates the flat-file configuration and license keys natively.</li>
     *   <li>Pre-computes the O(N) sensitive block bitset mapping.</li>
     *   <li>Mounts the {@link NettyInjector} to intercept raw outbound pipelines.</li>
     *   <li>Registers specialized Bukkit event listeners for proximity and logout events.</li>
     *   <li>Schedules the asynchronous Global Violation Profiler.</li>
     *   <li>Schedules the asynchronous Memory Leak Garbage Collector.</li>
     * </ol>
     */
    @Override
    public void onEnable() {
        try {
            Class.forName("com.angryguyy.metadatastripper.cache.BlockStateCache");
        } catch (ClassNotFoundException exception) {
            getLogger().log(java.util.logging.Level.WARNING,
                    "Unable to initialize the block state sanitization cache", exception);
        }

        saveDefaultConfig();

        ConfigurationValidator.ValidationResult initialValidation = validateConfiguration();
        ConfigurationValidator.log(getLogger(), initialValidation);
        if (!initialValidation.isValid()) {
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        loadConfiguration();

        if (sensitiveArrayInitializationFailure != null) {
            getLogger().log(java.util.logging.Level.WARNING,
                    "Unable to size the sensitive block state table from the native registry",
                    sensitiveArrayInitializationFailure);
        }

        if (!LicenseManager.validateLicense(this)) {
            getLogger().severe("================================================================");
            getLogger().severe(" [SECURITY ERROR] Invalid or missing license configuration!");
            getLogger().severe(" Please ensure 'client-name' and 'license-key' are properly set.");
            getLogger().severe(" Shutting down MetadataStripper.");
            getLogger().severe("================================================================");

            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        setupSensitiveBlocks();

        PluginManager pluginManager = getServer().getPluginManager();

        nettyInjector = new NettyInjector(this);
        pluginManager.registerEvents(nettyInjector, this);
        pluginManager.registerEvents(new PlayerQuitListener(), this);
        pluginManager.registerEvents(new BlockUpdateListener(), this);

        proximityRevealer = new ProximityRevealer(getConfig().getStringList("sensitive-blocks"));
        pluginManager.registerEvents(proximityRevealer, this);

        PluginCommand msCommand = getCommand("ms");
        if (msCommand != null) {
            msCommand.setExecutor(new AdminCommand(this));
        }

        Bukkit.getOnlinePlayers().forEach(nettyInjector::injectPlayer);

        EntityCullingEngine.initializeCullingTask(this);
        engineHealth = nettyInjector.isReady() ? EngineHealth.ACTIVE : EngineHealth.DEGRADED;

        profilerCancellation = RegionSchedulerAdapter.scheduleGlobalRepeating(this, () -> {
            for (org.bukkit.entity.Player player : Bukkit.getOnlinePlayers()) {
                int violations = BlockEntityFilter.getAndResetViolations(player.getUniqueId());
                if (violations >= alertThreshold) {
                    getLogger().log(java.util.logging.Level.WARNING,
                            "[Profiler] {0} triggered exploit threshold: {1} NBT packets in 60s.",
                            new Object[]{player.getName(), violations});

                    Component alertMsg = Component.text("[MS-Alert] ", NamedTextColor.DARK_RED)
                            .append(Component.text(player.getName(), NamedTextColor.YELLOW))
                            .append(Component.text(" generated anomalous NBT traffic: ", NamedTextColor.GRAY))
                            .append(Component.text(String.valueOf(violations), NamedTextColor.RED))
                            .append(Component.text(" in 60s. (Possible Stash Finder)", NamedTextColor.DARK_GRAY));

                    for (org.bukkit.entity.Player admin : Bukkit.getOnlinePlayers()) {
                        if (admin.hasPermission("metadatastripper.admin")) {
                            admin.sendMessage(alertMsg);
                        }
                    }
                }
            }
        }, 1200L, 1200L);

        gcCancellation = RegionSchedulerAdapter.scheduleGlobalRepeating(this, () -> {
            Set<UUID> activeUuids = new HashSet<>();
            for (org.bukkit.entity.Player player : Bukkit.getOnlinePlayers()) {
                activeUuids.add(player.getUniqueId());
            }
            BlockEntityFilter.cleanOrphans(activeUuids);
            EntityCullingEngine.cleanOrphans(activeUuids);
            if (proximityRevealer != null) {
                proximityRevealer.cleanOrphans(activeUuids);
            }
            if (nettyInjector != null) {
                nettyInjector.cleanOrphans(activeUuids);
            }
        }, 6000L, 6000L);

        getLogger().info("Lightweight Anti-Xray Engine enabled! Background tasks: 2, Dynamic Maps: 0");
    }

    /**
     * Parses the validated flat-file configuration settings into volatile memory fields.
     */
    private void loadConfiguration() {
        baseEngineMode = getConfig().getInt("engine-mode", 2);
        alertThreshold = getConfig().getInt("alert-threshold", 5000);
    }

    /**
     * Iterates through the native Mojang block registry to dynamically cross-reference configured
     * Bukkit materials, pre-computing the primitive boolean array.
     * <p>
     * This translates the user-friendly configuration strings into raw NMS internal IDs,
     * enabling the Netty outbound handlers to perform extreme fast-path network skipping
     * without crossing the Bukkit API boundary.
     */
    private void setupSensitiveBlocks() {
        Set<String> configuredMaterials = new HashSet<>();
        getConfig().getStringList("sensitive-blocks").forEach(material ->
                configuredMaterials.add(material.toUpperCase(Locale.ROOT)));

        boolean[] sensitiveStates = new boolean[Block.BLOCK_STATE_REGISTRY.size() + 1000];
        int failedStates = 0;

        for (int i = 0; i < sensitiveStates.length && i < Block.BLOCK_STATE_REGISTRY.size(); i++) {
            try {
                BlockState state = Block.stateById(i);
                if (state != null) {
                    org.bukkit.Material mat = state.createCraftBlockData().getMaterial();
                    if (configuredMaterials.contains(mat.name())) {
                        sensitiveStates[i] = true;
                    }
                }
            } catch (Exception exception) {
                failedStates++;
            }
        }

        sensitiveGlobal = sensitiveStates;

        BlockEntityFilter.updateSensitiveMaterials(configuredMaterials);

        if (failedStates > 0) {
            getLogger().log(java.util.logging.Level.WARNING,
                    "Unable to inspect {0} native block states while building the sensitive table",
                    failedStates);
        }
    }

    /**
     * Performs a live hot-reload of configurations and sensitive block profiles.
     * <p>
     * By pre-calculating the new state and applying it atomically, this guarantees O(1) registry
     * updates and a seamless transition for the active Netty pipeline, requiring no player disconnections
     * or pipeline un-injections.
     *
     * @return {@code true} when the new configuration was applied successfully; {@code false} when the configuration validator rejected it
     */
    public boolean reloadEngine() {
        reloadConfig();

        ConfigurationValidator.ValidationResult validation = validateConfiguration();
        ConfigurationValidator.log(getLogger(), validation);

        if (!validation.isValid()) {
            return false;
        }

        loadConfiguration();
        setupSensitiveBlocks();

        if (proximityRevealer != null) {
            proximityRevealer.updateConfiguredBlocks(getConfig().getStringList("sensitive-blocks"));
        }
        return true;
    }

    /**
     * Safely tears down the engine architecture during server shutdown or plugin disabling.
     * <p>
     * Specifically ensures that all Netty duplex handlers are gracefully detached from connected
     * clients and that all background schedulers and memory maps are strictly purged to aid the
     * Garbage Collector and prevent memory leaks.
     */
    @Override
    public void onDisable() {
        profilerCancellation.run();
        profilerCancellation = () -> { };

        gcCancellation.run();
        gcCancellation = () -> { };

        if (nettyInjector != null) {
            nettyInjector.shutdown();
            Bukkit.getOnlinePlayers().forEach(nettyInjector::removePlayer);
        }

        EntityCullingEngine.shutdown();
        BlockEntityFilter.clearAll();
        engineHealth = EngineHealth.DISABLED;

        getLogger().info("MetadataStripper successfully disabled and memory cleared.");
    }

    /**
     * Retrieves the active operational engine mode, applying dynamic TPS-based degradation heuristics.
     * <p>
     * If the server is configured to use the aggressive subterranean fill mode (Mode 2), but the
     * Server TPS drops below the critical 18.5 threshold, this method instantly steps the engine
     * down to Mode 1 (standard obfuscation) to relieve CPU pressure and prevent chunk timeouts.
     *
     * @return the active integer representing the target obfuscation engine strategy (1 or 2)
     */
    public int getEngineMode() {
        if (baseEngineMode > 1 && Bukkit.getTPS()[0] < 18.5) {
            return 1;
        }
        return baseEngineMode;
    }

    /**
     * Returns the current operational health state of the regional packet pipeline.
     *
     * @return the {@link EngineHealth} enum value
     */
    public EngineHealth getEngineHealth() {
        return engineHealth;
    }

    /**
     * Performs a dry-run validation of the currently loaded {@code config.yml} without
     * mutating any active runtime state. Used extensively by the {@code /ms validate} command.
     *
     * @return the immutable configuration validation result
     */
    public ConfigurationValidator.ValidationResult validateConfiguration() {
        return ConfigurationValidator.validate(getConfig());
    }

    /**
     * Safely increments the global telemetry counter for successfully rewritten chunk packets.
     * Called asynchronously by the player's Region Scheduler.
     */
    public static void recordTransformedChunk() {
        transformedChunkPackets.incrementAndGet();
    }

    /**
     * Records a critical packet fallback event where a chunk was forwarded un-obfuscated to the client.
     * Automatically degrades the {@link EngineHealth} to flag the anomaly.
     *
     * @param timeout {@code true} when the fallback was triggered by exceeding the regional wait deadline;
     *                {@code false} if triggered by an internal unhandled exception.
     */
    public static void recordFallback(boolean timeout) {
        fallbackChunkPackets.incrementAndGet();
        if (timeout) {
            packetTimeouts.incrementAndGet();
        } else {
            packetErrors.incrementAndGet();
        }
        if (engineHealth != EngineHealth.DISABLED) {
            engineHealth = EngineHealth.DEGRADED;
        }
    }

    /**
     * Records an event where a regional packet was utterly destroyed (dropped) because a specific
     * player's outbound channel reached its strict backpressure threshold (usually 32 pending packets).
     * Automatically degrades the {@link EngineHealth}.
     */
    public static void recordBackpressureDrop() {
        backpressureDrops.incrementAndGet();
        if (engineHealth != EngineHealth.DISABLED) {
            engineHealth = EngineHealth.DEGRADED;
        }
    }
}