package com.angryguyy.metadatastripper.commands;

import com.angryguyy.metadatastripper.MetadataStripper;
import com.angryguyy.metadatastripper.config.ConfigurationValidator;
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
 * such as hot-reloading the configuration, validating sensitive block configurations, or displaying
 * realtime engine telemetry and health diagnostics.
 * <p>
 * <b>Architectural Notes:</b>
 * <ul>
 *   <li><b>Execution Context:</b> Command execution typically occurs on the global region thread in Folia
 *       or the main server thread in Paper. Operations here should avoid blocking I/O, though configuration
 *       reloading involves file reading which is bounded and acceptable during administrative actions.</li>
 *   <li><b>Memory Efficiency:</b> Optimized to construct and dispatch complex Kyori Adventure components
 *       in a single packet/message, minimizing overhead during command execution.</li>
 *   <li><b>Diagnostics & Health:</b> The commands exposed here (specifically {@code /ms diagnose}) are
 *       critical for monitoring the regional packet pipeline. They expose backpressure drops, timeouts,
 *       and fallback events that could push the engine into a {@code DEGRADED} state.</li>
 * </ul>
 *
 * @see MetadataStripper#reloadEngine()
 * @see MetadataStripper#getEngineHealth()
 */
public final class AdminCommand implements CommandExecutor {

    private final MetadataStripper plugin;

    /**
     * Constructs the administrative command router.
     *
     * @param plugin the core engine instance used to trigger reloads, access health states,
     *               and retrieve global packet transformation counters.
     */
    public AdminCommand(MetadataStripper plugin) {
        this.plugin = plugin;
    }

    /**
     * Processes incoming command requests, enforcing permissions and routing execution paths.
     * <p>
     * <b>Available Subcommands:</b>
     * <ul>
     *   <li>{@code /ms reload}: Atomically hot-reloads the configuration and rebuilds sensitive material tables.</li>
     *   <li>{@code /ms diagnose}: Displays granular engine health, packet timeouts, backpressure drops, and fallback metrics.</li>
     *   <li>{@code /ms validate}: Performs a dry-run validation of the engine mode, license, and materials without applying changes.</li>
     *   <li>{@code /ms} (default): Displays basic high-level telemetry and engine status.</li>
     * </ul>
     * <p>
     * <b>Permission Requirement:</b> Requires {@code metadatastripper.admin}.
     *
     * @param sender  the entity executing the command (e.g., a player or the server console)
     * @param command the command that was executed
     * @param label   the alias used to execute the command
     * @param args    the command arguments (used to route to subcommands)
     * @return {@code true} always, to indicate that the command was processed successfully (even if syntax was incorrect, to prevent default Bukkit usage messages)
     */
    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        if (!sender.hasPermission("metadatastripper.admin")) {
            sender.sendMessage(Component.text("You do not have permission to execute this command.", NamedTextColor.RED));
            return true;
        }

        if (args.length > 0 && args[0].equalsIgnoreCase("reload")) {
            if (plugin.reloadEngine()) {
                sender.sendMessage(Component.text("MetadataStripper configuration and lookup tables reloaded successfully.", NamedTextColor.GREEN));
            } else {
                sender.sendMessage(Component.text("Reload rejected: configuration is invalid. Use /ms validate.", NamedTextColor.RED));
            }
            return true;
        }

        if (args.length > 0 && args[0].equalsIgnoreCase("diagnose")) {
            sender.sendMessage(Component.text("MetadataStripper diagnostics", NamedTextColor.AQUA, TextDecoration.BOLD));
            sender.sendMessage(Component.text("Health: " + plugin.getEngineHealth(), NamedTextColor.GRAY));

            sender.sendMessage(Component.text("Transformed chunks: " + MetadataStripper.transformedChunkPackets.get(), NamedTextColor.GRAY));
            sender.sendMessage(Component.text("Fallback chunks: " + MetadataStripper.fallbackChunkPackets.get(), NamedTextColor.GRAY));

            sender.sendMessage(Component.text("Timeouts: " + MetadataStripper.packetTimeouts.get(), NamedTextColor.GRAY));
            sender.sendMessage(Component.text("Packet errors: " + MetadataStripper.packetErrors.get(), NamedTextColor.GRAY));
            sender.sendMessage(Component.text("Backpressure drops: " + MetadataStripper.backpressureDrops.get(), NamedTextColor.GRAY));

            sender.sendMessage(Component.text("NBT blocked: " + MetadataStripper.interceptedNbtPackets.get(), NamedTextColor.GRAY));
            sender.sendMessage(Component.text("Entity packets blocked: " + MetadataStripper.interceptedEntityPackets.get(), NamedTextColor.GRAY));
            sender.sendMessage(Component.text("Entities culled: " + MetadataStripper.culledEntities.get(), NamedTextColor.GRAY));
            return true;
        }

        if (args.length > 0 && args[0].equalsIgnoreCase("validate")) {
            ConfigurationValidator.ValidationResult validation = plugin.validateConfiguration();
            if (validation.isValid()) {
                sender.sendMessage(Component.text("Configuration valid", NamedTextColor.GREEN));
            } else {
                sender.sendMessage(Component.text("Configuration invalid", NamedTextColor.RED));
            }

            validation.warnings().forEach(warning -> sender.sendMessage(
                    Component.text("Warning: " + warning, NamedTextColor.YELLOW)));
            validation.errors().forEach(error -> sender.sendMessage(
                    Component.text("Error: " + error, NamedTextColor.RED)));
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