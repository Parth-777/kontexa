package com.example.BACKEND.identity.connector;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * AES-256-GCM seal/unseal for warehouse connector credentials at rest.
 * Key: {@code KONTEXA_CONNECTOR_ENCRYPTION_KEY} (or {@code kontexa.connector.encryption-key})
 * — Base64-encoded 32-byte key. Never log plaintext or key material.
 */
@Service
public class ConnectorCredentialCipher {

    private static final Logger log = LoggerFactory.getLogger(ConnectorCredentialCipher.class);

    static final String ENVELOPE_PREFIX = "ENC:v1:";
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int GCM_TAG_BITS = 128;
    private static final int IV_LENGTH = 12;
    private static final int KEY_LENGTH = 32;

    private final SecretKey key;
    private final SecureRandom secureRandom = new SecureRandom();

    @Autowired
    public ConnectorCredentialCipher(
            @Value("${kontexa.connector.encryption-key:}") String configuredKey
    ) {
        String raw = firstNonBlank(System.getenv("KONTEXA_CONNECTOR_ENCRYPTION_KEY"), configuredKey);
        this.key = parseKey(raw);
        if (this.key == null) {
            log.warn("[connector-cipher] KONTEXA_CONNECTOR_ENCRYPTION_KEY not set; "
                    + "connector credentials will not be encrypted at rest");
        } else {
            log.info("[connector-cipher] AES-256-GCM connector credential encryption enabled");
        }
    }

    public boolean isEnabled() {
        return key != null;
    }

    public boolean isSealed(String stored) {
        return stored != null && stored.startsWith(ENVELOPE_PREFIX);
    }

    /**
     * Encrypt plaintext for DB storage. Already-sealed values are returned unchanged.
     * If encryption is not configured, returns plaintext (dev fallback).
     */
    public String seal(String plaintext) {
        if (plaintext == null) return null;
        if (isSealed(plaintext)) return plaintext;
        if (key == null) return plaintext;
        try {
            byte[] iv = new byte[IV_LENGTH];
            secureRandom.nextBytes(iv);
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_BITS, iv));
            byte[] ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            return ENVELOPE_PREFIX
                    + Base64.getEncoder().encodeToString(iv)
                    + ":"
                    + Base64.getEncoder().encodeToString(ciphertext);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Failed to encrypt connector credentials", e);
        }
    }

    /**
     * Decrypt DB value to plaintext. Legacy plaintext (non-envelope) is returned as-is
     * with {@link UnsealResult#wasLegacyPlaintext()} true so callers can re-seal.
     */
    public UnsealResult unseal(String stored) {
        if (stored == null || stored.isBlank()) {
            return UnsealResult.plaintext("", false);
        }
        if (!isSealed(stored)) {
            return UnsealResult.plaintext(stored, true);
        }
        if (key == null) {
            throw new IllegalStateException(
                    "Encrypted connector credentials present but KONTEXA_CONNECTOR_ENCRYPTION_KEY is not set");
        }
        try {
            String body = stored.substring(ENVELOPE_PREFIX.length());
            int sep = body.indexOf(':');
            if (sep <= 0 || sep == body.length() - 1) {
                throw new IllegalArgumentException("Malformed credential envelope");
            }
            byte[] iv = Base64.getDecoder().decode(body.substring(0, sep));
            byte[] ciphertext = Base64.getDecoder().decode(body.substring(sep + 1));
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_BITS, iv));
            byte[] plain = cipher.doFinal(ciphertext);
            return UnsealResult.plaintext(new String(plain, StandardCharsets.UTF_8), false);
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            throw new IllegalStateException("Failed to decrypt connector credentials (tampered or wrong key)", e);
        }
    }

    public record UnsealResult(String plaintext, boolean wasLegacyPlaintext) {
        static UnsealResult plaintext(String value, boolean legacy) {
            return new UnsealResult(value, legacy);
        }
    }

    private static String firstNonBlank(String a, String b) {
        if (a != null && !a.isBlank()) return a.trim();
        if (b != null && !b.isBlank()) return b.trim();
        return null;
    }

    private static SecretKey parseKey(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try {
            byte[] decoded = Base64.getDecoder().decode(raw.trim());
            if (decoded.length != KEY_LENGTH) {
                throw new IllegalArgumentException(
                        "KONTEXA_CONNECTOR_ENCRYPTION_KEY must decode to 32 bytes (got " + decoded.length + ")");
            }
            return new SecretKeySpec(decoded, "AES");
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("Invalid KONTEXA_CONNECTOR_ENCRYPTION_KEY: " + e.getMessage(), e);
        }
    }

    /** Generate a Base64 32-byte key (for docs/tests). */
    public static String generateKeyBase64() {
        byte[] bytes = new byte[KEY_LENGTH];
        new SecureRandom().nextBytes(bytes);
        return Base64.getEncoder().encodeToString(bytes);
    }
}
