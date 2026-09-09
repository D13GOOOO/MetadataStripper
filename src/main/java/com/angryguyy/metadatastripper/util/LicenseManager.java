package com.angryguyy.metadatastripper.util;

import com.angryguyy.metadatastripper.MetadataStripper;
import org.bukkit.configuration.file.FileConfiguration;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;

/**
 * Algorithmic local license validator.
 * <p>
 * This utility generates and validates cryptographic signature keys bound directly to the client's
 * configured username/identifier. It serves as an installation gate during plugin startup without
 * requiring external remote server lookups or constant plugin recompilation.
 * <p>
 * <b>Architectural Notes:</b>
 * <ul>
 *   <li><b>Execution Context:</b> Invoked strictly during the plugin's {@code onEnable()} bootstrap phase
 *       on the main server thread or global region thread.</li>
 *   <li><b>Security Design:</b> Employs standard SHA-256 hashing coupled with URL-safe Base64 encoding.
 *       Crucially, validation utilizes {@link MessageDigest#isEqual(byte[], byte[])} to perform a constant-time
 *       byte array comparison, successfully neutralizing potential timing attacks against the license key.</li>
 * </ul>
 */
public final class LicenseManager {

    /**
     * Cryptographic salt used to anchor the license generation algorithm and prevent simple rainbow-table lookups.
     */
    private static final String SECRET_SALT = "MetadataStripper-Secret-Salt-2026-X9!z";

    /**
     * Private constructor to prevent instantiation of this utility class.
     *
     * @throws UnsupportedOperationException if called via reflection.
     */
    private LicenseManager() {
        throw new UnsupportedOperationException("Utility class cannot be instantiated.");
    }

    /**
     * Validates the configuration's license key by checking if it mathematically matches
     * the generated signature for the specified client name.
     * <p>
     * <b>Timing Attack Prevention:</b> Uses {@link MessageDigest#isEqual(byte[], byte[])} rather
     * than standard string equality ({@code equals()}), ensuring comparison execution time is independent
     * of the number of matching bytes.
     *
     * @param plugin the main plugin instance used to access configuration parameters ({@code client-name} and {@code license-key})
     * @return {@code true} if the provided license key matches the client's cryptographic signature; {@code false} otherwise
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
     * Generates the valid cryptographic license key for a given client identifier.
     * <p>
     * This utility method can also be used externally by license provisioning tools or automated panels
     * to generate the exact key that must be delivered to a customer.
     * <p>
     * <b>Generation Logic:</b> Converts the client name to lowercase, appends the secret salt,
     * computes a SHA-256 hash, URL-encodes it without padding, and formats it as an uppercase string
     * prefixed with {@code MS-} (truncated to 16 characters).
     *
     * @param clientName the username or unique corporate identifier of the buyer
     * @return the unique cryptographic license key string, or {@code "INVALID-KEY"} if a cryptographic exception occurs
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