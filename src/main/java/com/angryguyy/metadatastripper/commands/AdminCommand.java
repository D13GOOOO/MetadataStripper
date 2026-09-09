package com.angryguyy.metadatastripper.commands;

import com.angryguyy.metadatastripper.MetadataStripper;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.jetbrains.annotations.NotNull;

/**
 * Administrative command router for the MetadataStripper engine.
 * <p>
 * Evaluates administrative execution requests and delegates them to the appropriate subsystem,
 * such as hot-reloading the configuration or displaying realtime engine telemetry.
 * Optimized to construct and dispatch complex Kyori Adventure components in a single packet.
 */
public final class AdminCommand implements CommandExecutor {

    private final MetadataStripper plugin;

    /**
     * Constructs the administrative command router.
     *
     * @param plugin the core engine instance used to trigger reloads and access states
     */
    public AdminCommand(MetadataStripper plugin) {
        this.plugin = plugin;
    }

    /**
     * Processes incoming command requests, handling permissions and routing execution paths.
     *
     * @param sender  the entity executing the command
     * @param command the command executed
     * @param label   the alias used
     * @param args    the command arguments
     * @return true to indicate successful execution
     */
    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        if (!sender.hasPermission("metadatastripper.admin")) {
            sender.sendMessage(Component.text("You do not have permission to execute this command.", NamedTextColor.RED));
            return true;
        }

        if (args.length > 0 && args[0].equalsIgnoreCase("reload")) {
            plugin.reloadEngine();
            sender.sendMessage(Component.text("MetadataStripper configuration and lookup tables reloaded seamlessly!", NamedTextColor.GREEN));
            return true;
        }

        long nbt = MetadataStripper.interceptedNbtPackets.get();
        long entities = MetadataStripper.interceptedEntityPackets.get();
        long culled = MetadataStripper.culledEntities.get();

        Component telemetry = Component.text("-----------------------------------\n", NamedTextColor.DARK_GRAY)
                .append(Component.text(" MetadataStripper Telemetry\n\n", NamedTextColor.AQUA, TextDecoration.BOLD))
                .append(Component.text(" NBT Packets Destroyed: ", NamedTextColor.GRAY))
                .append(Component.text(String.format("%,d", nbt) + "\n", NamedTextColor.GREEN))
                .append(Component.text(" Entity Data Blocked: ", NamedTextColor.GRAY))
                .append(Component.text(String.format("%,d", entities) + "\n", NamedTextColor.GREEN))
                .append(Component.text(" Entities Culled: ", NamedTextColor.GRAY))
                .append(Component.text(String.format("%,d", culled) + "\n\n", NamedTextColor.GREEN))
                .append(Component.text(" Engine Status: ", NamedTextColor.DARK_GRAY))
                .append(Component.text("REGIONAL PACKET PIPELINE\n", NamedTextColor.GREEN))
                .append(Component.text("-----------------------------------", NamedTextColor.DARK_GRAY));

        sender.sendMessage(telemetry);

        return true;
    }
}