package com.angryguyy.metadatastripper.config;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.logging.Logger;

import org.bukkit.Material;
import org.bukkit.configuration.file.FileConfiguration;

/**
 * Validates user-provided MetadataStripper configuration before it is applied to the engine.
 * <p>
 * This utility acts as a strict gatekeeper. By reporting invalid values as discrete errors
 * before committing them, it ensures that both initial startup and subsequent hot-reloads
 * (via {@code /ms reload}) always preserve a known-good, immutable runtime state.
 * <p>
 * <b>Architectural Notes:</b>
 * <ul>
 *   <li><b>Execution Context:</b> Designed to run during plugin initialization and administrative command execution
 *       (typically on the main server thread in Paper or the global region thread in Folia). It is <i>not</i>
 *       invoked during the outbound packet path.</li>
 *   <li><b>Performance Implications:</b> Enforces a soft limit of 128 materials for {@code sensitive-blocks}.
 *       Exceeding this triggers a warning because larger sets linearly increase the O(N) cost of scanning
 *       chunk sections in the player's region scheduler, potentially leading to packet timeouts or backpressure drops.</li>
 *   <li><b>Thread Safety:</b> The validation logic is stateless, and the resulting {@link ValidationResult}
 *       is deeply immutable and completely thread-safe to read across different schedulers.</li>
 * </ul>
 */
public final class ConfigurationValidator {

    /**
     * Private constructor to prevent instantiation of this utility class.
     *
     * @throws UnsupportedOperationException if called via reflection.
     */
    private ConfigurationValidator() {
        throw new UnsupportedOperationException("Utility class cannot be instantiated.");
    }

    /**
     * Validates operational settings and configured Bukkit materials against engine constraints.
     * <p>
     * <b>Validation Rules:</b>
     * <ul>
     *   <li>{@code engine-mode}: Must be 1 (standard) or 2 (aggressive subterranean fill).</li>
     *   <li>{@code alert-threshold}: Must be strictly greater than 0.</li>
     *   <li>{@code client-name} & {@code license-key}: Must be present and non-blank (acts as a local installation gate).</li>
     *   <li>{@code sensitive-blocks}: Must not be empty, and all entries must successfully map to valid Bukkit {@link Material} names.</li>
     *   <li>{@code advanced}: All tuning parameters must be within their safe operational limits.</li>
     * </ul>
     *
     * @param configuration the raw {@link FileConfiguration} to inspect (typically loaded from {@code config.yml})
     * @return an immutable {@link ValidationResult} containing discrete lists of blocking errors and non-blocking warnings
     * @see org.bukkit.Material#matchMaterial(String)
     */
    public static ValidationResult validate(FileConfiguration configuration) {
        List<String> errors = new ArrayList<>();
        List<String> warnings = new ArrayList<>();

        int engineMode = configuration.getInt("engine-mode", -1);
        if (engineMode < 1 || engineMode > 2) {
            errors.add("engine-mode must be 1 or 2");
        }

        int alertThreshold = configuration.getInt("alert-threshold", -1);
        if (alertThreshold < 1) {
            errors.add("alert-threshold must be greater than zero");
        }

        String clientName = configuration.getString("client-name", "");
        if (clientName.isBlank()) {
            errors.add("client-name is required");
        }

        String licenseKey = configuration.getString("license-key", "");
        if (licenseKey.isBlank()) {
            errors.add("license-key is required");
        }

        double degradationTps = configuration.getDouble("advanced.degradation-tps-threshold", -1.0);
        if (degradationTps <= 0.0 || degradationTps > 20.0) {
            errors.add("advanced.degradation-tps-threshold must be between 0.1 and 20.0");
        }

        double tacticalRadius = configuration.getDouble("advanced.tactical-culling-radius", -1.0);
        if (tacticalRadius <= 0.0) {
            errors.add("advanced.tactical-culling-radius must be greater than zero");
        }

        int maxPendingWrites = configuration.getInt("advanced.max-pending-region-writes", -1);
        if (maxPendingWrites < 1) {
            errors.add("advanced.max-pending-region-writes must be at least 1");
        }

        int proximityRadius = configuration.getInt("advanced.proximity-radius", -1);
        if (proximityRadius < 1) {
            errors.add("advanced.proximity-radius must be at least 1");
        }

        int raytraceDistance = configuration.getInt("advanced.raytrace-max-distance", -1);
        if (raytraceDistance < 1) {
            errors.add("advanced.raytrace-max-distance must be at least 1");
        }

        List<String> configuredMaterials = configuration.getStringList("sensitive-blocks");
        if (configuredMaterials.isEmpty()) {
            errors.add("sensitive-blocks must contain at least one material");
        }

        int invalidMaterials = 0;
        for (String configuredMaterial : configuredMaterials) {
            if (configuredMaterial == null
                    || Material.matchMaterial(configuredMaterial.toUpperCase(Locale.ROOT)) == null) {
                invalidMaterials++;
            }
        }
        if (invalidMaterials > 0) {
            errors.add(invalidMaterials + " sensitive-blocks entries are not valid Bukkit materials");
        }

        if (configuredMaterials.size() > 128) {
            warnings.add("sensitive-blocks contains more than 128 materials and may increase chunk transformation cost");
        }

        return new ValidationResult(errors, warnings);
    }

    /**
     * Dispatches a validation result to the designated logger using stable severity levels.
     * <p>
     * Errors are logged at {@link java.util.logging.Level#SEVERE}, while warnings are logged
     * at {@link java.util.logging.Level#WARNING}.
     *
     * @param logger the destination {@link Logger} (typically the plugin's primary logger)
     * @param result the validation result payload to report
     */
    public static void log(Logger logger, ValidationResult result) {
        result.errors().forEach(error -> logger.log(java.util.logging.Level.SEVERE, "[Configuration] {0}", error));
        result.warnings().forEach(warning -> logger.log(java.util.logging.Level.WARNING, "[Configuration] {0}", warning));
    }

    /**
     * An immutable data carrier representing the outcome of a configuration validation pass.
     * <p>
     * <b>Thread Safety:</b> Both internal collections are isolated via {@link List#copyOf(java.util.Collection)},
     * ensuring this record can be safely shared across the asynchronous Netty event loop and Bukkit schedulers.
     *
     * @param errors   a strict list of blocking configuration errors. If not empty, the configuration must be rejected.
     * @param warnings a list of non-blocking configuration warnings (e.g., performance advisories).
     */
    public record ValidationResult(List<String> errors, List<String> warnings) {

        /**
         * Compact constructor that enforces deep immutability of the enclosed lists.
         *
         * @param errors   the collection of blocking configuration errors
         * @param warnings the collection of non-blocking configuration warnings
         */
        public ValidationResult {
            errors = List.copyOf(errors);
            warnings = List.copyOf(warnings);
        }

        /**
         * Determines whether the evaluated configuration is safe to be applied to the engine.
         *
         * @return {@code true} if no blocking errors exist; {@code false} otherwise. Warnings do not affect validity.
         */
        public boolean isValid() {
            return errors.isEmpty();
        }
    }
}