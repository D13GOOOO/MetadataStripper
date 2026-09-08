package com.angryguyy.metadatastripper.util;

import org.bukkit.Bukkit;
import org.bukkit.entity.Entity;
import org.bukkit.plugin.Plugin;

/**
 * Provides a unified scheduling interface to support both Paper and Folia server implementations.
 * Automatically detects the server environment and dispatches tasks to the appropriate regional or global scheduler.
 * <p>
 * <b>Algorithmic Complexity:</b>
 * <ul>
 *   <li><b>Environment Check:</b> O(1) constant-time boolean read (evaluated once at class-load).</li>
 *   <li><b>Memory Footprint:</b> Zero-GC. Directly passes Runnable references without lambda wrapping.</li>
 * </ul>
 */
public final class RegionSchedulerAdapter {

    private static final boolean IS_FOLIA = checkFolia();

    private RegionSchedulerAdapter() {
        throw new UnsupportedOperationException("Utility class cannot be instantiated.");
    }

    /**
     * Detects if the server software is a Folia environment.
     *
     * @return true if running on Folia, false otherwise
     */
    private static boolean checkFolia() {
        try {
            Class.forName("io.papermc.paper.threadedregions.RegionizedServer");
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

    /**
     * Executes a task for a specific entity on its designated region thread.
     *
     * @param plugin the plugin instance
     * @param entity the entity determining the regional thread
     * @param task the execution payload
     */
    public static void executeForEntity(Plugin plugin, Entity entity, Runnable task) {
        if (IS_FOLIA) {
            entity.getScheduler().execute(plugin, task, null, 1L);
        } else {
            Bukkit.getScheduler().runTask(plugin, task);
        }
    }
}