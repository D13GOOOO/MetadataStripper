package com.angryguyy.metadatastripper.util;

import org.bukkit.Bukkit;
import org.bukkit.entity.Entity;
import org.bukkit.plugin.Plugin;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

/**
 * Provides a unified scheduling interface to support both Paper and Folia server implementations.
 * Automatically detects the server environment and dispatches tasks to the appropriate regional or global scheduler.
 * <p>The adapter keeps global Bukkit access on the global scheduler and entity-owned access on
 * the appropriate player scheduler when running on Folia.
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

    /**
     * Executes a computation on the owning entity scheduler and waits for its result.
     *
     * @param plugin plugin owning the scheduled task
     * @param entity entity selecting the Paper or Folia execution context
     * @param task computation that must access entity-owned server state
     * @param timeout maximum wait duration
     * @param unit timeout unit
     * @param <T> computation result type
     * @return the value produced by the scheduled computation
     * @throws InterruptedException if the waiting thread is interrupted
     * @throws ExecutionException if the scheduled computation fails
     * @throws TimeoutException if the scheduler does not complete in time
     */
    public static <T> T callForEntity(Plugin plugin, Entity entity, Supplier<T> task, long timeout, TimeUnit unit)
            throws InterruptedException, ExecutionException, TimeoutException {
        CompletableFuture<T> result = new CompletableFuture<>();
        executeForEntity(plugin, entity, () -> {
            try {
                result.complete(task.get());
            } catch (Throwable throwable) {
                result.completeExceptionally(throwable);
            }
        });
        return result.get(timeout, unit);
    }

    /**
     * Schedules a repeating task on the global server scheduler.
     *
     * @param plugin plugin owning the scheduled task
     * @param task task that may access global Bukkit state
     * @param initialDelayTicks initial delay in server ticks
     * @param periodTicks repetition period in server ticks
     */
    public static void scheduleGlobalRepeating(Plugin plugin, Runnable task, long initialDelayTicks, long periodTicks) {
        if (IS_FOLIA) {
            Bukkit.getGlobalRegionScheduler().runAtFixedRate(plugin, scheduledTask -> task.run(), initialDelayTicks, periodTicks);
        } else {
            Bukkit.getScheduler().runTaskTimer(plugin, task, initialDelayTicks, periodTicks);
        }
    }
}