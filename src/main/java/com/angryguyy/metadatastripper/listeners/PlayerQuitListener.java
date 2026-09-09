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
 * A highly optimized listener designed to neutralize "Logout Spot" and combat-logging exploit modules.
 * <p>
 * Rather than modifying the quitting player's native NMS coordinates (which triggers asynchronous
 * chunk saves and can corrupt the player's persistent location data on modern Folia/Paper architectures),
 * this class intercepts the disconnection process and instantly broadcasts a native entity destruction
 * packet ({@link ClientboundRemoveEntitiesPacket}) to all viewers in the same world. This forces tracking
 * clients (such as unauthorized ESPs) to purge the player's "ghost" entity immediately, effectively
 * neutralizing ambush coordinates without triggering unnecessary chunk loads or disk I/O.
 * <p>
 * Furthermore, this listener acts as the primary garbage collection trigger for the plugin's internal caches,
 * safely evicting the quitting player's UUID from lock-free tracking maps.
 * <p>
 * <b>Architectural Notes:</b>
 * <ul>
 *   <li><b>Execution Context:</b> Executes synchronously on the main server thread (Paper) or the global region
 *       thread (Folia) during the player disconnect sequence.</li>
 *   <li><b>Memory Management:</b> Vital for preventing memory leaks. It strictly purges the disconnecting player's
 *       data profiles, ensuring no orphaned arrays remain in memory. Allocates only a single packet instance for the entire broadcast.</li>
 *   <li><b>Algorithmic Complexity:</b> O(P) execution time, where P is the number of active players in the quitting
 *       player's world, utilizing lightning-fast reference equality checks.</li>
 * </ul>
 */
public final class PlayerQuitListener implements Listener {

    /**
     * Creates the disconnect cleanup listener.
     * <p>
     * Must be registered with the Bukkit {@link org.bukkit.plugin.PluginManager} during plugin startup.
     */
    public PlayerQuitListener() {
    }

    /**
     * Intercepts player disconnections to broadcast instantaneous entity removal packets
     * and safely purge the player's internal memory profiles.
     * <p>
     * <b>Priority Strategy:</b> Registered at the {@link EventPriority#HIGHEST} priority level to ensure
     * this listener acts at the tail-end of the event chain, right before the server finalizes the quit,
     * guaranteeing the cleanup executes regardless of other plugins modifying the quit event.
     *
     * @param event the native {@link PlayerQuitEvent} dispatched by the server
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