package com.angryguyy.metadatastripper.data;

/**
 * A lightweight object wrapper for primitive {@code long} values, primarily used for packed chunk coordinates.
 * <p>
 * Unlike {@link java.lang.Long}, this custom wrapper allows for extensibility.
 * By utilizing a mutable extension of this wrapper ({@code MutableLongWrapper}) during map lookups,
 * the plugin completely avoids continuous object allocation (Autoboxing) during high-frequency
 * asynchronous ray-tracing. This saves the Garbage Collector (GC) from severe strain and preserves server TPS.
 */
public class LongWrapper {

    protected long value;

    /**
     * Constructs a new LongWrapper.
     *
     * @param value the primitive long value to wrap
     */
    public LongWrapper(long value) {
        this.value = value;
    }

    /**
     * Retrieves the wrapped primitive long value.
     *
     * @return the underlying long value
     */
    public final long getValue() {
        return this.value;
    }

    @Override
    public final boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (obj instanceof LongWrapper other) {
            return this.value == other.value;
        }
        return false;
    }

    @Override
    public final int hashCode() {
        return Long.hashCode(this.value);
    }
}