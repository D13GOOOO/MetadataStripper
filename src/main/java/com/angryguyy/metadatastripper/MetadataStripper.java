package com.angryguyy.metadatastripper;

import com.angryguyy.metadatastripper.listeners.NettyInjector;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Main plugin class for MetadataStripper, responsible for lifecycle management
 * and initializing the native Netty packet injection pipeline.
 */
public class MetadataStripper extends JavaPlugin {

    private NettyInjector nettyInjector;

    /**
     * Called when the plugin is enabled. Initializes components and registers player pipelines.
     */
    @Override
    public void onEnable() {
        getLogger().info("Initializing Native MetadataStripper for 1.21.1...");

        nettyInjector = new NettyInjector();
        getServer().getPluginManager().registerEvents(nettyInjector, this);

        Bukkit.getOnlinePlayers().forEach(nettyInjector::injectPlayer);

        getLogger().info("Netty pipeline injected successfully. Sus Chunk Finders are blinded.");
    }

    /**
     * Called when the plugin is disabled. Cleans up pipelines for all connected players.
     */
    @Override
    public void onDisable() {
        getLogger().info("Shutting down MetadataStripper. Cleaning up pipelines...");

        if (nettyInjector != null) {
            Bukkit.getOnlinePlayers().forEach(nettyInjector::removePlayer);
        }
    }
}