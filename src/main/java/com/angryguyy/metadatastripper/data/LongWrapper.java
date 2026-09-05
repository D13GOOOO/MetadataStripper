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

    /**
     * The primitive long value representing packed chunk coordinates.
     * Declared as protected to allow rapid, direct modification by subclasses
     * without the overhead of accessor methods.
     */
    protected long value;

    /**
     * Constructs a new LongWrapper.
     *
     * @param value The primitive long value to wrap.
     */
    public LongWrapper(long value) {
        this.value = value;
    }

    /**
     * Retrieves the wrapped primitive long value.
     *
     * @return The underlying long value.
     */
    public final long getValue() {
        return this.value;
    }

    /**
     * Highly optimized equality check for ultra-fast map lookups.
     */
    @Override
    public final boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (obj instanceof LongWrapper) {
            return this.value == ((LongWrapper) obj).value;
        }
        return false;
    }

    /**
     * Highly optimized hash code generation for ultra-fast map lookups.
     */
    @Override
    public final int hashCode() {
        return Long.hashCode(this.value);
    }
}