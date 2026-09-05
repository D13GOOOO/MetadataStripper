package com.angryguyy.metadatastripper.listeners;

import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.util.Vector;

import com.angryguyy.metadatastripper.MetadataStripper;
import com.angryguyy.metadatastripper.data.PlayerData;
import com.angryguyy.metadatastripper.data.VectorialLocation;
import com.angryguyy.metadatastripper.tasks.RayTraceCallable;
import com.angryguyy.metadatastripper.tasks.UpdateBukkitRunnable;

public final class PlayerListener implements Listener {
    private final MetadataStripper plugin;

    public PlayerListener(MetadataStripper plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();

        if (!plugin.validatePlayer(player)) return;

        PlayerData playerData = new PlayerData(MetadataStripper.getLocations(player, new VectorialLocation(player.getEyeLocation())));
        playerData.setCallable(new RayTraceCallable(plugin, playerData));
        plugin.getPlayerData().put(player.getUniqueId(), playerData);

        if (plugin.isFolia()) {
            player.getScheduler().runAtFixedRate(plugin, new UpdateBukkitRunnable(plugin, player), null, 1L, plugin.getUpdateTicks());
        }
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        plugin.getPlayerData().remove(event.getPlayer().getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerMove(PlayerMoveEvent event) {
        Player player = event.getPlayer();
        PlayerData playerData = plugin.getPlayerData().get(player.getUniqueId());

        if (!plugin.validatePlayerData(player, playerData, "onPlayerMove")) return;

        Location to = event.getTo();

        if (to != null && to.getWorld().equals(playerData.getLocations()[0].getWorld())) {
            VectorialLocation location = new VectorialLocation(to);
            Vector vector = location.getVector();
            vector.setY(vector.getY() + player.getEyeHeight());
            playerData.setLocations(MetadataStripper.getLocations(player, location));
        }
    }
}