package com.angryguyy.metadatastripper.listeners;

import com.angryguyy.metadatastripper.network.BlockEntityFilter;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;

/**
 * Intercepts player disconnections to neutralize Logout Spot and combat logging exploit modules.
 * <p>
 * Modifies the native NMS entity position coordinates instantaneously prior to the server broadcasting
 * the entity removal packet. This forces tracking clients to record the logout location at the maximum
 * build height, effectively neutralizing ambush coordinates without triggering chunk loads or GC allocations.
 * Also securely clears memory profiles associated with the player to maintain a true zero-GC footprint.
 */
public final class PlayerQuitListener implements Listener {

    /**
     * Mutates the disconnecting player's altitude primitive to the specific world ceiling in O(1) time
     * and purges the player's tracking profile from memory.
     *
     * @param event the native player quit event
     */
    @SuppressWarnings("unused")
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPlayerQuit(PlayerQuitEvent event) {
        CraftPlayer craftPlayer = (CraftPlayer) event.getPlayer();

        double x = craftPlayer.getHandle().getX();
        double z = craftPlayer.getHandle().getZ();
        double maxY = craftPlayer.getWorld().getMaxHeight();

        craftPlayer.getHandle().setPos(x, maxY, z);

        BlockEntityFilter.removeProfile(craftPlayer.getUniqueId());
    }
}