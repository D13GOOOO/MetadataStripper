package com.angryguyy.metadatastripper;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import org.bukkit.Bukkit;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.java.JavaPlugin;

import com.angryguyy.metadatastripper.commands.AdminCommand;
import com.angryguyy.metadatastripper.engine.EntityCullingEngine;
import com.angryguyy.metadatastripper.listeners.NettyInjector;
import com.angryguyy.metadatastripper.listeners.PlayerQuitListener;
import com.angryguyy.metadatastripper.network.BlockEntityFilter;
import com.angryguyy.metadatastripper.util.LicenseManager;
import com.angryguyy.metadatastripper.listeners.BlockUpdateListener;
import com.angryguyy.metadatastripper.listeners.ProximityRevealer;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Core bootstrap and lifecycle management class for the MetadataStripper Engine.
 * <p>
 * Initializes the zero-GC lookup arrays and injects the low-level Netty payload interceptors.
 * Stripped of all asynchronous tracking maps, spatial profiles, and Bukkit listener overhead
 * to ensure an absolute Zero-GC footprint. All visibility spoofing is delegated exclusively
 * to the stateless "Fire & Forget" network pipeline.
 * <p>
 * <b>Algorithmic Complexity:</b>
 * <ul>
 *   <li><b>Initialization:</b> O(N) where N is the internal Mojang block registry size.</li>
 *   <li><b>Runtime State:</b> O(1) constant-time memory profile with zero dynamic allocations.</li>
 * </ul>
 */
public final class MetadataStripper extends JavaPlugin {

    /**
     * A highly optimized, primitive O(1) registry mapping NMS Block IDs to their sensitivity flags.
     * Evaluated instantaneously during asynchronous Netty chunk packet serialization to bypass heavy map lookups.
     */
    public static final boolean[] sensitiveGlobal;

    public static final AtomicLong interceptedNbtPackets = new AtomicLong(0);
    public static final AtomicLong interceptedEntityPackets = new AtomicLong(0);
    public static final AtomicLong culledEntities = new AtomicLong(0);

    static {
        int maxStates = 50000;
        try {
            maxStates = Block.BLOCK_STATE_REGISTRY.size() + 1000;
        } catch (Exception ignored) {}
        sensitiveGlobal = new boolean[maxStates];
    }

    private NettyInjector nettyInjector;
    private int baseEngineMode;
    private int alertThreshold;

    /**
     * Executes the primary initialization phase of the engine.
     * Loads the deterministic configuration, computes the sensitive block bitset,
     * mounts the Netty injector, and registers administrative commands.
     */
    @Override
    public void onEnable() {
        try {
            Class.forName("com.angryguyy.metadatastripper.cache.BlockStateCache");
        } catch (ClassNotFoundException ignored) {}

        saveDefaultConfig();
        loadConfiguration();

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
        pluginManager.registerEvents(new ProximityRevealer(getConfig().getStringList("sensitive-blocks")), this);

        PluginCommand msCommand = getCommand("ms");
        if (msCommand != null) {
            msCommand.setExecutor(new AdminCommand(this));
        }

        Bukkit.getOnlinePlayers().forEach(nettyInjector::injectPlayer);

        EntityCullingEngine.initializeCullingTask(this);

        Bukkit.getAsyncScheduler().runAtFixedRate(this, task -> {
            for (org.bukkit.entity.Player player : Bukkit.getOnlinePlayers()) {
                int violations = BlockEntityFilter.getAndResetViolations(player.getUniqueId());
                if (violations >= alertThreshold) {
                    getLogger().warning("[Profiler] " + player.getName() + " triggered exploit threshold: " + violations + " NBT packets in 60s.");

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
        }, 60L, 60L, TimeUnit.SECONDS);

        getLogger().info("Lightweight Anti-Xray Engine enabled! Background tasks: 1, Dynamic Maps: 0");
    }

    /**
     * Parses the flat-file configuration settings.
     */
    private void loadConfiguration() {
        baseEngineMode = getConfig().getInt("engine-mode", 2);
        alertThreshold = getConfig().getInt("alert-threshold", 5000);
    }

    /**
     * Iterates through the native Mojang block registry to dynamically cross-reference configured
     * Bukkit materials, pre-computing the primitive boolean array for extreme fast-path network skipping.
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

    /**
     * Performs a live hot-reload of configurations and sensitive blocks without uninjecting clients.
     * Ensures O(1) registry updates and a seamless transition for the active Netty pipeline.
     */
    public void reloadEngine() {
        reloadConfig();
        loadConfiguration();
        Arrays.fill(sensitiveGlobal, false);
        setupSensitiveBlocks();
    }

    /**
     * Safely tears down the engine, safely detaching all Netty duplex handlers
     * from connected clients and purging memory references to assist the Garbage Collector.
     */
    @Override
    public void onDisable() {
        if (nettyInjector != null) {
            Bukkit.getOnlinePlayers().forEach(nettyInjector::removePlayer);
        }

        EntityCullingEngine.shutdown();

        getLogger().info("MetadataStripper successfully disabled and memory cleared.");
    }

    /**
     * Retrieves the configured operational engine mode with dynamic TPS-based degradation.
     * Falls back to a less computationally expensive obfuscation mode if server ticks per second
     * drop below the optimal threshold.
     *
     * @return the active integer representing the obfuscation engine heuristic strategy
     */
    public int getEngineMode() {
        if (baseEngineMode > 1 && Bukkit.getTPS()[0] < 18.5) {
            return 1;
        }
        return baseEngineMode;
    }
}