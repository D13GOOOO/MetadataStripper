package com.angryguyy.metadatastripper.config;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

class ConfigurationValidatorTest {

    @Test
    void acceptsCompleteConfiguration() {
        YamlConfiguration configuration = new YamlConfiguration();
        configuration.set("engine-mode", 2);
        configuration.set("alert-threshold", 5000);
        configuration.set("client-name", "customer");
        configuration.set("license-key", "MS-valid");
        configuration.set("sensitive-blocks", java.util.List.of("DIAMOND_ORE", "CHEST"));

        // Aggiungiamo i valori avanzati richiesti dal nuovo validatore
        configuration.set("advanced.degradation-tps-threshold", 18.5);
        configuration.set("advanced.tactical-culling-radius", 32.0);
        configuration.set("advanced.max-pending-region-writes", 32);
        configuration.set("advanced.proximity-radius", 5);
        configuration.set("advanced.raytrace-max-distance", 45);

        ConfigurationValidator.ValidationResult result = ConfigurationValidator.validate(configuration);

        assertTrue(result.isValid(), "Configuration should be valid but had errors: " + result.errors());
        assertTrue(result.errors().isEmpty());
    }

    @Test
    void rejectsInvalidOperationalValuesAndMaterials() {
        YamlConfiguration configuration = new YamlConfiguration();
        configuration.set("engine-mode", 3);
        configuration.set("alert-threshold", 0);
        configuration.set("client-name", "customer");
        configuration.set("license-key", "MS-invalid");
        configuration.set("sensitive-blocks", java.util.List.of("NOT_A_MATERIAL"));

        // Impostiamo deliberatamente valori errati anche qui
        configuration.set("advanced.degradation-tps-threshold", 25.0); // Troppo alto (>20)
        configuration.set("advanced.tactical-culling-radius", -5.0); // Negativo
        configuration.set("advanced.max-pending-region-writes", 0); // Troppo basso (<1)
        configuration.set("advanced.proximity-radius", -1); // Negativo
        configuration.set("advanced.raytrace-max-distance", 0); // Troppo basso (<1)

        ConfigurationValidator.ValidationResult result = ConfigurationValidator.validate(configuration);

        assertFalse(result.isValid());
        assertTrue(result.errors().size() >= 3);
    }
}