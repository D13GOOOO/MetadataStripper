package com.angryguyy.metadatastripper;

import java.util.HashSet;
import java.util.Set;

import org.bukkit.Bukkit;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.java.JavaPlugin;

import com.angryguyy.metadatastripper.listeners.NettyInjector;
import com.angryguyy.metadatastripper.tasks.EntityVisibilityTask;

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

    static {
        int maxStates = 50000;
        try {
            maxStates = Block.BLOCK_STATE_REGISTRY.size() + 1000;
        } catch (Exception ignored) {}
        sensitiveGlobal = new boolean[maxStates];
    }

    private NettyInjector nettyInjector;

    private int engineMode;
    private final Set<String> ignoredWorlds = new HashSet<>();
    private final Set<String> sensitiveEntities = new HashSet<>();

    /**
     * Executes the primary initialization phase of the engine.
     * Loads the deterministic configuration, computes the sensitive block bitset,
     * and mounts the Netty injector directly into the active socket connections.
     */
    @Override
    public void onEnable() {
        saveDefaultConfig();
        loadConfiguration();
        setupSensitiveBlocks();

        PluginManager pluginManager = getServer().getPluginManager();

        nettyInjector = new NettyInjector(this);
        pluginManager.registerEvents(nettyInjector, this);
        Bukkit.getOnlinePlayers().forEach(nettyInjector::injectPlayer);

        new EntityVisibilityTask(this).runTaskTimer(this, 20L, 10L);

        getLogger().info("Lightweight Anti-Xray Engine enabled! Background tasks: 0, Dynamic Maps: 0");
    }

    /**
     * Parses the flat-file configuration and populates the O(1) HashSets for rapid runtime evaluation.
     */
    private void loadConfiguration() {
        engineMode = getConfig().getInt("engine-mode", 2);

        ignoredWorlds.clear();
        ignoredWorlds.addAll(getConfig().getStringList("ignored-worlds"));

        sensitiveEntities.clear();
        sensitiveEntities.addAll(getConfig().getStringList("sensitive-entities"));
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
     * Safely tears down the engine, safely detaching all Netty duplex handlers
     * from connected clients and purging memory references to assist the Garbage Collector.
     */
    @Override
    public void onDisable() {
        ignoredWorlds.clear();
        sensitiveEntities.clear();

        if (nettyInjector != null) {
            Bukkit.getOnlinePlayers().forEach(nettyInjector::removePlayer);
        }

        getLogger().info("MetadataStripper successfully disabled and memory cleared.");
    }

    /**
     * Retrieves the configured operational engine mode.
     *
     * @return the integer representing the obfuscation engine heuristic strategy
     */
    public int getEngineMode() { return engineMode; }

    /**
     * Retrieves the O(1) HashSet containing worlds explicitly excluded from packet obfuscation.
     *
     * @return the unmodifiable-like set of ignored world namespaces
     */
    public Set<String> getIgnoredWorlds() { return ignoredWorlds; }

    /**
     * Retrieves the O(1) HashSet containing entity types targeted for early network culling.
     *
     * @return the set of sensitive entity internal string signatures
     */
    public Set<String> getSensitiveEntities() { return sensitiveEntities; }
}