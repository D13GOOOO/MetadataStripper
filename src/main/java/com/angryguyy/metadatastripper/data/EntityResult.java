package com.angryguyy.metadatastripper.data;

import org.bukkit.entity.Entity;

/**
 * Thread-safe transport record for asynchronous entity culling results.
 *
 * @param entity  the tracked entity
 * @param visible the calculated visibility state
 */
public record EntityResult(Entity entity, boolean visible) {

    /**
     * Retrieves the tracked entity.
     *
     * @return the Bukkit entity
     */
    public Entity getEntity() {
        return this.entity;
    }

    /**
     * Retrieves the calculated visibility state.
     *
     * @return true if visible, false otherwise
     */
    public boolean isVisible() {
        return this.visible;
    }
}