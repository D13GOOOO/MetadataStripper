package com.angryguyy.metadatastripper.data;

/**
 * A mutable extension of {@link LongWrapper} designed specifically for ultra-fast,
 * Zero-GC (Garbage Collection) map lookups.
 * <p>
 * During high-frequency asynchronous ray-tracing, querying the chunk map thousands of
 * times per second with standard objects would cause severe memory allocation overhead.
 * By instantiating this class once per thread and mutating its internal value via {@link #setValue(long)},
 * the plugin can query Maps without ever allocating new objects in memory.
 */
public final class MutableLongWrapper extends LongWrapper {

    /**
     * Constructs a new MutableLongWrapper.
     *
     * @param value the initial primitive long value
     */
    public MutableLongWrapper(long value) {
        super(value);
    }

    /**
     * Mutates the internal long value of this wrapper.
     * <p>
     * <b>Warning:</b> This method alters the object's identity.
     * This object should never be mutated while it is actively stored as a key inside a Map,
     * otherwise the Map will lose the reference. It must only be used as a reusable probe
     * for map lookups.
     *
     * @param value the new primitive long value to wrap
     */
    public void setValue(long value) {
        this.value = value;
    }
}