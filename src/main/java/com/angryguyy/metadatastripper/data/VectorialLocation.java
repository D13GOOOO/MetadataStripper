package com.angryguyy.metadatastripper.data;

import java.lang.ref.Reference;
import java.lang.ref.WeakReference;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.util.Vector;

/**
 * An asynchronous-safe representation of a player's spatial location and viewing direction.
 * <p>
 * This class isolates pure mathematical coordinates ({@link Vector}) from the native Bukkit
 * {@link Location} object. Passing Bukkit's native Location to asynchronous threads is highly
 * discouraged as it holds strong references to the {@link World}, which causes massive memory leaks
 * if a world unloads. By utilizing a {@link WeakReference} for the World and cloning the vectors,
 * this DTO safely transfers location data to the Ray-Tracing threads with zero risk of leaks or
 * AsyncCatcher crashes.
 */
public final class VectorialLocation {

    /**
     * Weakly references the Bukkit World to prevent preventing memory leaks upon world unloading.
     */
    private final Reference<World> world;

    /**
     * The pure mathematical vector representing the spatial coordinates (x, y, z).
     */
    private final Vector vector;

    /**
     * The normalized mathematical vector representing the viewing direction (yaw, pitch).
     */
    private final Vector direction;

    /**
     * Constructs a new VectorialLocation from its pure mathematical components.
     *
     * @param world     the Bukkit World (will be weakly referenced).
     * @param vector    the spatial coordinate vector.
     * @param direction the viewing direction vector.
     */
    public VectorialLocation(World world, Vector vector, Vector direction) {
        this.world = new WeakReference<>(world);
        this.vector = vector;
        this.direction = direction;
    }

    /**
     * Copy constructor that safely clones the vectors to prevent cross-thread mutation issues.
     *
     * @param location the original {@link VectorialLocation} to clone.
     */
    public VectorialLocation(VectorialLocation location) {
        this.world = location.world;
        this.vector = location.getVector().clone();
        this.direction = location.getDirection().clone();
    }

    /**
     * Extracts the mathematical components from a native Bukkit Location safely.
     *
     * @param location the native Bukkit {@link Location}.
     */
    public VectorialLocation(Location location) {
        this(location.getWorld(), location.toVector(), location.getDirection());
    }

    /**
     * Retrieves the Bukkit World if it is still loaded in memory.
     *
     * @return the {@link World}, or null if it has been unloaded and cleared by the GC.
     */
    public World getWorld() {
        return this.world.get();
    }

    /**
     * Retrieves the spatial coordinate vector.
     *
     * @return the coordinate {@link Vector}.
     */
    public Vector getVector() {
        return this.vector;
    }

    /**
     * Retrieves the normalized viewing direction vector.
     *
     * @return the direction {@link Vector}.
     */
    public Vector getDirection() {
        return this.direction;
    }
}