package com.angryguyy.metadatastripper.util;

import com.angryguyy.metadatastripper.MetadataStripper;
import org.bukkit.configuration.file.FileConfiguration;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;

/**
 * Algorithmic local license validator.
 * Generates and validates cryptographic signature keys bound to the client's username/ID
 * without requiring external server lookups or constant plugin recompilation.
 */
public final class LicenseManager {

    private static final String SECRET_SALT = "MetadataStripper-Secret-Salt-2026-X9!z";

    private LicenseManager() {
        throw new UnsupportedOperationException("Utility class cannot be instantiated.");
    }

    /**
     * Validates the license key by checking if it mathematically matches the client identifier.
     *
     * @param plugin the main plugin instance
     * @return true if the license key matches the client's generated signature
     */
    public static boolean validateLicense(MetadataStripper plugin) {
        FileConfiguration config = plugin.getConfig();
        String clientName = config.getString("client-name", "").trim();
        String licenseKey = config.getString("license-key", "").trim();

        if (clientName.isEmpty() || licenseKey.isEmpty()) {
            return false;
        }

        String expectedKey = generateKeyForClient(clientName);

        return MessageDigest.isEqual(
                expectedKey.getBytes(StandardCharsets.UTF_8),
                licenseKey.getBytes(StandardCharsets.UTF_8)
        );
    }

    /**
     * Utility method to generate the valid license key for a given client.
    * The method can be used by a license provisioning tool to generate the key delivered to a customer.
     *
     * @param clientName the username or identifier of the buyer
     * @return the unique cryptographic license key
     */
    public static String generateKeyForClient(String clientName) {
        try {
            String raw = clientName.toLowerCase() + ":" + SECRET_SALT;
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(raw.getBytes(StandardCharsets.UTF_8));

            String encoded = Base64.getUrlEncoder().withoutPadding().encodeToString(hash);
            return "MS-" + encoded.substring(0, 16).toUpperCase();
        } catch (Exception e) {
            return "INVALID-KEY";
        }
    }
}