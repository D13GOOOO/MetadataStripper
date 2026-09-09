package com.angryguyy.metadatastripper.util;

import com.angryguyy.metadatastripper.MetadataStripper;
import org.bukkit.configuration.file.FileConfiguration;

import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

/**
 * Asymmetric cryptographic license validator.
 * <p>
 * This utility validates ECDSA (Elliptic Curve Digital Signature Algorithm) cryptographic signatures
 * bound to the client's configured username. By utilizing a Public Key Infrastructure (PKI) approach,
 * the plugin can securely verify licenses offline without ever exposing the private generation key
 * within the distributed JAR file, rendering decompilation and reverse-engineering completely ineffective.
 * <p>
 * <b>Architectural Notes:</b>
 * <ul>
 *   <li><b>Execution Context:</b> Invoked strictly during the plugin's {@code onEnable()} bootstrap phase
 *       on the main server thread or global region thread.</li>
 *   <li><b>Security Design:</b> Employs {@code SHA256withECDSA}. The client JAR contains only the X.509
 *       Public Key. The corresponding Private Key must remain secured on your distribution backend to
 *       generate the valid Base64 signature strings provided to buyers.</li>
 * </ul>
 */
public final class LicenseManager {

    /**
     * The X.509 encoded Elliptic Curve (EC) Public Key in Base64 format.
     * REPLACE THIS with your actual generated public key for production.
     */
    private static final String PUBLIC_KEY_BASE64 = "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAErlaGgYDRodpJlgu3EGr2xWI81CcRUR7IpbfsqaklY6zPjQEAI1V6hROKGw+TdVXfs9XlsTWxZ4OaADGqpn8wlA==";

    private LicenseManager() {
        throw new UnsupportedOperationException("Utility class cannot be instantiated.");
    }

    /**
     * Validates the configuration's license key by verifying its ECDSA signature against the client name.
     *
     * @param plugin the main plugin instance used to access configuration parameters
     * @return {@code true} if the provided license key is a mathematically valid signature signed by your private key; {@code false} otherwise
     */
    public static boolean validateLicense(MetadataStripper plugin) {
        FileConfiguration config = plugin.getConfig();
        String clientName = config.getString("client-name", "").trim();
        String licenseKey = config.getString("license-key", "").trim();

        if (clientName.isEmpty() || licenseKey.isEmpty()) {
            return false;
        }

        try {
            byte[] publicKeyBytes = Base64.getDecoder().decode(PUBLIC_KEY_BASE64);
            X509EncodedKeySpec keySpec = new X509EncodedKeySpec(publicKeyBytes);
            KeyFactory keyFactory = KeyFactory.getInstance("EC");
            PublicKey publicKey = keyFactory.generatePublic(keySpec);

            Signature signature = Signature.getInstance("SHA256withECDSA");
            signature.initVerify(publicKey);
            signature.update(clientName.toLowerCase().getBytes(StandardCharsets.UTF_8));

            byte[] signatureBytes = Base64.getDecoder().decode(licenseKey);
            return signature.verify(signatureBytes);
        } catch (Exception e) {
            return false;
        }
    }
}