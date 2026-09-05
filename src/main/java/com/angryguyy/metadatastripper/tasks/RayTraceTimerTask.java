package com.angryguyy.metadatastripper.tasks;

import java.util.TimerTask;
import java.util.concurrent.RejectedExecutionException;

import com.angryguyy.metadatastripper.MetadataStripper;

public final class RayTraceTimerTask extends TimerTask {
    private final MetadataStripper plugin;

    public RayTraceTimerTask(MetadataStripper plugin) {
        this.plugin = plugin;
    }

    @Override
    public void run() {
        boolean timingsEnabled = plugin.isTimingsEnabled();
        long start = timingsEnabled ? System.currentTimeMillis() : 0L;

        try {
            plugin.getExecutorService().invokeAll(plugin.getPlayerData().values());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (RejectedExecutionException e) {
        }

        if (timingsEnabled) {
            long stop = System.currentTimeMillis();
            plugin.getLogger().info((stop - start) + "ms per ray trace tick.");
        }
    }
}