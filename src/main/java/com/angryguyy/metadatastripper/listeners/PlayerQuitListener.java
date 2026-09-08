package com.angryguyy.metadatastripper.listeners;

import com.angryguyy.metadatastripper.engine.EntityCullingEngine;
import com.angryguyy.metadatastripper.network.BlockEntityFilter;
import net.minecraft.network.protocol.game.ClientboundRemoveEntitiesPacket;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;

/**
 * A highly optimized listener designed to neutralize Logout Spot and combat logging exploit modules.
 * <p>
 * Rather than modifying the quitting player's native NMS coordinates (which triggers asynchronous
 * chunk saves and corrupts the player's persistent location data on modern server architectures),
 * this class intercepts the disconnection process and instantly broadcasts a native entity destruction
 * packet to all viewers. This forces tracking clients (ESPs) to purge the player's ghost entity
 * immediately, effectively neutralizing ambush coordinates without triggering chunk loads.
 * <p>
 * <b>Algorithmic Complexity:</b>
 * <ul>
 *   <li><b>Memory Allocation:</b> Zero-GC footprint beyond the single packet instantiation.</li>
 *   <li><b>Execution Time:</b> O(P) where P is the number of active players in the quitting player's world, using lightning-fast reference equality checks.</li>
 * </ul>
 */
public final class PlayerQuitListener implements Listener {

    /**
     * Intercepts player disconnections at the HIGHEST priority to broadcast instantaneous
     * entity removal packets and safely purge the player's internal memory profiles.
     *
     * @param event the native player quit event
     */
    @SuppressWarnings("unused")
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPlayerQuit(PlayerQuitEvent event) {
        CraftPlayer craftPlayer = (CraftPlayer) event.getPlayer();

        ClientboundRemoveEntitiesPacket vanishPacket = new ClientboundRemoveEntitiesPacket(craftPlayer.getEntityId());

        for (Player viewer : craftPlayer.getWorld().getPlayers()) {
            if (viewer != craftPlayer) {
                ((CraftPlayer) viewer).getHandle().connection.send(vanishPacket);
            }
        }

        BlockEntityFilter.removeProfile(craftPlayer.getUniqueId());
        EntityCullingEngine.removePlayer(craftPlayer.getUniqueId());
    }
}