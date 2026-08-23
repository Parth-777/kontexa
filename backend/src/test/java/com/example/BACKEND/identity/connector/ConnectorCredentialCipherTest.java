package com.example.BACKEND.identity.connector;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConnectorCredentialCipherTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ConnectorCredentialCipher cipher;

    @BeforeEach
    void setUp() {
        cipher = new ConnectorCredentialCipher(ConnectorCredentialCipher.generateKeyBase64());
    }

    @Test
    void seal_neverPersistsPlaintextEnvelope() {
        String plain = "{\"provider\":\"bigquery\",\"projectId\":\"p\",\"serviceAccountJson\":\"secret\"}";
        String sealed = cipher.seal(plain);
        assertTrue(cipher.isSealed(sealed));
        assertFalse(sealed.contains("serviceAccountJson"));
        assertFalse(sealed.contains("secret"));
        assertNotEquals(plain, sealed);
    }

    @Test
    void unseal_restoresIdenticalConfig() {
        String plain = "{\"provider\":\"snowflake\",\"password\":\"p@ss\",\"account\":\"xy12345\"}";
        String sealed = cipher.seal(plain);
        ConnectorCredentialCipher.UnsealResult result = cipher.unseal(sealed);
        assertEquals(plain, result.plaintext());
        assertFalse(result.wasLegacyPlaintext());
    }

    @Test
    void tamperedCiphertext_fails() {
        String sealed = cipher.seal("{\"provider\":\"bigquery\"}");
        String tampered = sealed.substring(0, sealed.length() - 4) + "XXXX";
        assertThrows(IllegalStateException.class, () -> cipher.unseal(tampered));
    }

    @Test
    void wrongKey_fails() {
        String sealed = cipher.seal("{\"provider\":\"bigquery\"}");
        ConnectorCredentialCipher otherCipher =
                new ConnectorCredentialCipher(ConnectorCredentialCipher.generateKeyBase64());
        assertThrows(IllegalStateException.class, () -> otherCipher.unseal(sealed));
    }

    @Test
    void legacyPlaintext_detectedForMigration() {
        String legacy = "{\"provider\":\"bigquery\",\"projectId\":\"p\"}";
        ConnectorCredentialCipher.UnsealResult result = cipher.unseal(legacy);
        assertTrue(result.wasLegacyPlaintext());
        assertEquals(legacy, result.plaintext());
        String resealed = cipher.seal(result.plaintext());
        assertTrue(cipher.isSealed(resealed));
        assertEquals(legacy, cipher.unseal(resealed).plaintext());
    }

    @Test
    void seal_isIdempotentForAlreadySealed() {
        String sealed = cipher.seal("{\"a\":1}");
        assertEquals(sealed, cipher.seal(sealed));
    }

    /**
     * Realistic BigQuery outer connector JSON with nested service-account JSON whose
     * private_key is a multiline PEM (escaped {@code \\n} in the SA JSON string).
     * Proves AES-GCM seal/unseal and legacy reseal do not alter private_key bytes.
     */
    @Test
    void sealUnseal_preservesMultilinePemPrivateKey() throws Exception {
        String saJson = ""
                + "{"
                + "\"type\":\"service_account\","
                + "\"project_id\":\"demo-project\","
                + "\"private_key_id\":\"abc123def456\","
                + "\"client_email\":\"svc@demo-project.iam.gserviceaccount.com\","
                + "\"private_key\":\"-----BEGIN PRIVATE KEY-----\\n"
                + "MIIEvQIBADANBgkqhkiG9w0BAQEFAASCBKcwggSjAgEAAoIBAQC7VJTUt9Us8cKB\\n"
                + "wINqJXfjIdRhhyxvKClqHZJhHqDLK9x0pQ8mY2nO4rT6uV8wX0yZ1aB2cD3eF4g\\n"
                + "-----END PRIVATE KEY-----\\n\""
                + "}";

        ObjectNode outer = MAPPER.createObjectNode();
        outer.put("provider", "bigquery");
        outer.put("projectId", "demo-project");
        outer.put("location", "US");
        outer.put("dataset", "analytics");
        outer.put("connectionLink", "bigquery://demo-project");
        outer.put("serviceAccountJson", saJson);
        String plain = MAPPER.writeValueAsString(outer);

        String sealed = cipher.seal(plain);
        assertTrue(cipher.isSealed(sealed));
        assertFalse(sealed.contains("BEGIN PRIVATE KEY"));
        assertFalse(sealed.contains("demo-project"));

        String roundTrip = cipher.unseal(sealed).plaintext();
        assertEquals(plain, roundTrip);
        assertEquals(sha256(plain), sha256(roundTrip));

        // Legacy migrate path: plaintext → seal → unseal
        ConnectorCredentialCipher.UnsealResult legacy = cipher.unseal(plain);
        assertTrue(legacy.wasLegacyPlaintext());
        String resealed = cipher.seal(legacy.plaintext());
        String afterMigrate = cipher.unseal(resealed).plaintext();
        assertEquals(plain, afterMigrate);

        JsonNode saNode = MAPPER.readTree(MAPPER.readTree(afterMigrate).path("serviceAccountJson").asText());
        String privateKey = saNode.path("private_key").asText();
        assertTrue(privateKey.contains("BEGIN PRIVATE KEY"));
        assertTrue(privateKey.contains("END PRIVATE KEY"));
        assertTrue(privateKey.indexOf('\n') > 0, "PEM must contain real newlines after JSON parse");
        assertEquals("demo-project", saNode.path("project_id").asText());
        assertEquals("abc123def456", saNode.path("private_key_id").asText());
        assertFalse(saNode.path("client_email").asText().isBlank());
        assertEquals(sha256(saJson), sha256(MAPPER.readTree(afterMigrate).path("serviceAccountJson").asText()));
    }

    private static String sha256(String value) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8));
        return HexFormat.of().formatHex(digest);
    }
}
