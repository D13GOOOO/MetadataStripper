package com.angryguyy.metadatastripper.tasks;

import java.util.TimerTask;
import java.util.concurrent.RejectedExecutionException;

import com.angryguyy.metadatastripper.MetadataStripper;

/**
 * The master metronome for the asynchronous Ray-Tracing engine.
 * <p>
 * This scheduled task runs strictly in the background (off the main server thread)
 * every 50 milliseconds. It acts as the dispatcher, simultaneously triggering the
 * mathematical ray-trace calculations for every active player across the available
 * CPU cores in the Executor thread pool.
 */
public final class RayTraceTimerTask extends TimerTask {

    private final MetadataStripper plugin;

    /**
     * Constructs the RayTraceTimerTask.
     *
     * @param plugin the main plugin instance.
     */
    public RayTraceTimerTask(MetadataStripper plugin) {
        this.plugin = plugin;
    }

    @Override
    public void run() {
        boolean timingsEnabled = plugin.isTimingsEnabled();

        // Performance: System.nanoTime() is hardware-level accurate and faster than currentTimeMillis()
        long start = timingsEnabled ? System.nanoTime() : 0L;

        try {
            /*
             * Dispatches all PlayerData ray-trace callables to the ThreadPool.
             * invokeAll blocks this specific Timer thread until all calculations are complete,
             * ensuring we don't start a new ray-trace cycle before the previous one finishes.
             */
            plugin.getExecutorService().invokeAll(plugin.getPlayerData().values());

        } catch (InterruptedException e) {
            // Restore the interrupted status to let the thread shut down gracefully
            Thread.currentThread().interrupt();

        } catch (RejectedExecutionException e) {
            /*
             * Swallowed intentionally.
             * This exception occurs harmlessly during server shutdown or plugin reload
             * when the ExecutorService is terminated but this Timer hasn't completely stopped yet.
             */
        }

        if (timingsEnabled) {
            long stop = System.nanoTime();
            // Convert nanoseconds back to fractional milliseconds for readable logging
            double elapsedMs = (stop - start) / 1_000_000.0;
            plugin.getLogger().info(String.format("%.2fms per ray trace tick.", elapsedMs));
        }
    }
}