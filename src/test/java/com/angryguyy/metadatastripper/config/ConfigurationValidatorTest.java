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

        ConfigurationValidator.ValidationResult result = ConfigurationValidator.validate(configuration);

        assertTrue(result.isValid());
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

        ConfigurationValidator.ValidationResult result = ConfigurationValidator.validate(configuration);

        assertFalse(result.isValid());
        assertTrue(result.errors().size() >= 3);
    }
}
