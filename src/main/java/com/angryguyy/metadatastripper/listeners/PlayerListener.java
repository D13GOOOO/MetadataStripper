package com.angryguyy.metadatastripper.listeners;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.util.Vector;

import com.angryguyy.metadatastripper.MetadataStripper;
import com.angryguyy.metadatastripper.data.PlayerData;
import com.angryguyy.metadatastripper.data.VectorialLocation;
import com.angryguyy.metadatastripper.tasks.RayTraceCallable;
import com.angryguyy.metadatastripper.tasks.UpdateBukkitRunnable;

/**
 * Listens for player lifecycle and movement events to manage their asynchronous ray-tracing profiles.
 * <p>
 * This class ensures that the Ray-Tracer always has the most up-to-date mathematical
 * coordinates (eye location and directional vectors) for every active player.
 */
public final class PlayerListener implements Listener {

    private final MetadataStripper plugin;

    /**
     * Constructs the PlayerListener.
     *
     * @param plugin the main plugin instance
     */
    public PlayerListener(MetadataStripper plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();

        if (!plugin.validatePlayer(player)) {
            return;
        }

        PlayerData playerData = new PlayerData(
                MetadataStripper.getLocations(player, new VectorialLocation(player.getEyeLocation()))
        );

        playerData.setCallable(new RayTraceCallable(plugin, playerData));
        plugin.getPlayerData().put(player.getUniqueId(), playerData);

        if (plugin.isFolia()) {
            player.getScheduler().runAtFixedRate(
                    plugin,
                    new UpdateBukkitRunnable(plugin, player),
                    null,
                    1L,
                    plugin.getUpdateTicks()
            );
        }
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        plugin.getPlayerData().remove(event.getPlayer().getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerMove(PlayerMoveEvent event) {
        handleMovement(event.getPlayer(), event.getFrom(), event.getTo());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerTeleport(PlayerTeleportEvent event) {
        handleMovement(event.getPlayer(), event.getFrom(), event.getTo());
    }

    private void handleMovement(Player player, Location from, Location to) {
        if (to == null || (from.getX() == to.getX()
                && from.getY() == to.getY()
                && from.getZ() == to.getZ()
                && from.getYaw() == to.getYaw()
                && from.getPitch() == to.getPitch())) {
            return;
        }

        World world = to.getWorld();

        if (world == null || plugin.getIgnoredWorlds().contains(world.getName())) {
            return;
        }

        PlayerData playerData = plugin.getPlayerData().get(player.getUniqueId());
        if (!plugin.validatePlayerData(player, playerData, "handleMovement")) {
            return;
        }

        VectorialLocation location = new VectorialLocation(to);
        Vector vector = location.getVector();
        vector.setY(vector.getY() + player.getEyeHeight());

        playerData.setLocations(MetadataStripper.getLocations(player, location));
    }
}