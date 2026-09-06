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
     * @param plugin the main plugin instance
     */
    public RayTraceTimerTask(MetadataStripper plugin) {
        this.plugin = plugin;
    }

    @Override
    public void run() {
        boolean timingsEnabled = plugin.isTimingsEnabled();
        long start = timingsEnabled ? System.nanoTime() : 0L;

        try {
            plugin.getExecutorService().invokeAll(plugin.getPlayerData().values());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (RejectedExecutionException ignored) {
        }

        if (timingsEnabled) {
            long stop = System.nanoTime();
            double elapsedMs = (stop - start) / 1_000_000.0;
            plugin.getLogger().info(String.format("%.2fms per ray trace tick.", elapsedMs));
        }
    }
}