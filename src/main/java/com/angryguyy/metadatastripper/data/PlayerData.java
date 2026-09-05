package com.angryguyy.metadatastripper.data;

import java.util.Queue;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ConcurrentMap;

/**
 * Holds the asynchronous state and thread-safe data structures for a single player.
 * <p>
 * This class acts as the high-performance, lock-free bridge between the main server threads
 * (Bukkit and Netty, which update locations and consume packets) and the asynchronous
 * Ray-Tracing thread (which reads locations and calculates visibility).
 */
public final class PlayerData implements Callable<Object> {

    /**
     * The player's eye locations.
     * Marked as {@code volatile} to guarantee immediate memory visibility across threads
     * without the massive CPU overhead of synchronization locks.
     */
    private volatile VectorialLocation[] locations;

    /**
     * A thread-safe map containing the chunks loaded by this player.
     * Handled via a {@link ConcurrentHashMap} so Netty can safely inject/remove chunks
     * while the Ray-Tracer iterates over them without causing ConcurrentModificationExceptions.
     */
    private final ConcurrentMap<LongWrapper, ChunkBlocks> chunks = new ConcurrentHashMap<>();

    /**
     * A lock-free, highly concurrent queue holding the results of the ray-tracing calculations.
     * The Ray-Tracer acts as the producer, and the Bukkit/Netty thread acts as the consumer.
     */
    private final Queue<Result> results = new ConcurrentLinkedQueue<>();

    /**
     * The asynchronous task (e.g., RayTraceCallable) assigned to this player.
     */
    private Callable<?> callable;

    /**
     * Constructs a new PlayerData instance for a specific player.
     *
     * @param locations the initial vectorial locations (eyes) of the player.
     */
    public PlayerData(VectorialLocation[] locations) {
        this.locations = locations;
    }

    public VectorialLocation[] getLocations() {
        return this.locations;
    }

    public void setLocations(VectorialLocation[] locations) {
        this.locations = locations;
    }

    public ConcurrentMap<LongWrapper, ChunkBlocks> getChunks() {
        return this.chunks;
    }

    public Queue<Result> getResults() {
        return this.results;
    }

    public Callable<?> getCallable() {
        return this.callable;
    }

    public void setCallable(Callable<?> callable) {
        this.callable = callable;
    }

    /**
     * Executes the assigned callable task asynchronously.
     *
     * @return the result of the callable execution (usually null for background loops).
     * @throws Exception if the underlying callable throws an exception.
     */
    @Override
    public Object call() throws Exception {
        // Safety check to prevent NullPointerException from crashing the ThreadPool worker
        if (this.callable != null) {
            return this.callable.call();
        }
        return null;
    }
}