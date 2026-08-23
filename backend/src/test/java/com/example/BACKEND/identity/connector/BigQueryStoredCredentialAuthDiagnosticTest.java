package com.example.BACKEND.identity.connector;

import com.example.BACKEND.tenant.BigQueryConnectorService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.Properties;

/**
 * Live diagnosis only: load the newest workspace_connectors row, report storage
 * envelope form (no secret content), and call {@link BigQueryConnectorService#testConnection}
 * with the currently stored credentials. Does not touch DecisionRuntime.
 *
 * Safe stdout markers only — never prints credentials or keys.
 */
class BigQueryStoredCredentialAuthDiagnosticTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void diagnoseStoredBigQueryCredentials() throws Exception {
        Properties props = loadDatasourceProps();
        String url = props.getProperty("spring.datasource.url");
        String user = props.getProperty("spring.datasource.username");
        String password = props.getProperty("spring.datasource.password");
        if (blank(url) || blank(user) || blank(password)) {
            System.out.println("DIAG_BQ_AUTH=SKIP reason=missing_datasource_props");
            System.out.println("DIAG_STORED_FORMAT=UNKNOWN");
            return;
        }

        // Prefer env / local file only. Ignore unresolved Spring placeholders like ${KONTEXA_...}.
        String encryptionKey = resolvedEncryptionKey(props);
        ConnectorCredentialCipher cipher = new ConnectorCredentialCipher(
                encryptionKey == null ? "" : encryptionKey);

        String storedRaw;
        String warehouseType;
        try (Connection conn = DriverManager.getConnection(url, user, password);
             PreparedStatement ps = conn.prepareStatement("""
                     SELECT warehouse_type, encrypted_credentials
                     FROM workspace_connectors
                     WHERE status = 'CONNECTED' AND warehouse_type = 'BIGQUERY'
                     ORDER BY updated_at DESC
                     LIMIT 1
                     """);
             ResultSet rs = ps.executeQuery()) {
            if (!rs.next()) {
                System.out.println("DIAG_BQ_AUTH=FAIL");
                System.out.println("DIAG_AUTH_ERROR=no_connected_workspace_connector_row");
                System.out.println("DIAG_STORED_FORMAT=EMPTY");
                System.out.println("DIAG_NEEDS_FRESH_SA_JSON=YES");
                return;
            }
            warehouseType = rs.getString(1);
            storedRaw = rs.getString(2);
        }

        String format = classifyFormat(storedRaw);
        System.out.println("DIAG_STORED_FORMAT=" + format);
        System.out.println("DIAG_WAREHOUSE_TYPE=" + (warehouseType == null ? "" : warehouseType));
        System.out.println("DIAG_CIPHER_ENABLED=" + cipher.isEnabled());

        String plaintext;
        try {
            plaintext = cipher.unseal(storedRaw == null ? "" : storedRaw).plaintext();
        } catch (RuntimeException ex) {
            System.out.println("DIAG_BQ_AUTH=FAIL");
            System.out.println("DIAG_AUTH_ERROR=unseal_failed:" + sanitize(ex.getMessage()));
            System.out.println("DIAG_NEEDS_FRESH_SA_JSON=YES");
            return;
        }

        if (blank(plaintext) || !plaintext.trim().startsWith("{")) {
            System.out.println("DIAG_BQ_AUTH=FAIL");
            System.out.println("DIAG_AUTH_ERROR=connector_payload_not_json_object");
            System.out.println("DIAG_NEEDS_FRESH_SA_JSON=YES");
            return;
        }

        JsonNode root = MAPPER.readTree(plaintext);
        String provider = root.path("provider").asText("").toLowerCase();
        if (!provider.isBlank() && !"bigquery".equals(provider)) {
            System.out.println("DIAG_BQ_AUTH=FAIL");
            System.out.println("DIAG_AUTH_ERROR=newest_connector_not_bigquery provider=" + sanitize(provider));
            System.out.println("DIAG_NEEDS_FRESH_SA_JSON=NO");
            return;
        }

        String projectId = root.path("projectId").asText("").trim();
        String location = root.path("location").asText("").trim();
        String dataset = root.path("dataset").asText("").trim();
        String serviceAccountJson = extractServiceAccountJson(root.get("serviceAccountJson"));

        System.out.println("DIAG_HAS_PROJECT_ID=" + !projectId.isBlank());
        System.out.println("DIAG_HAS_DATASET=" + !dataset.isBlank());
        System.out.println("DIAG_HAS_SERVICE_ACCOUNT_JSON=" + !serviceAccountJson.isBlank());
        System.out.println("DIAG_SA_JSON_STARTS_WITH_BRACE=" + serviceAccountJson.trim().startsWith("{"));
        System.out.println("DIAG_SA_HAS_PEM_MARKERS="
                + (serviceAccountJson.contains("BEGIN PRIVATE KEY")
                && serviceAccountJson.contains("END PRIVATE KEY")));
        System.out.println("DIAG_CREDENTIAL_SOURCE=workspace_connectors.persisted");
        System.out.println("DIAG_PATH_A_TEST_CONNECTION=request_body_not_persisted");
        System.out.println("DIAG_PATH_B_WAREHOUSE=persisted_BIGQUERY_row");

        if (projectId.isBlank() || serviceAccountJson.isBlank()) {
            System.out.println("DIAG_BQ_AUTH=FAIL");
            System.out.println("DIAG_AUTH_ERROR=missing_projectId_or_serviceAccountJson");
            System.out.println("DIAG_NEEDS_FRESH_SA_JSON=YES");
            return;
        }

        BigQueryConnectorService bq = new BigQueryConnectorService();
        try {
            bq.testConnection(projectId, serviceAccountJson, location, dataset);
            System.out.println("DIAG_BQ_AUTH=PASS");
            System.out.println("DIAG_AUTH_ERROR=");
            System.out.println("DIAG_NEEDS_FRESH_SA_JSON=NO");
        } catch (Exception ex) {
            String msg = sanitize(rootCauseMessage(ex));
            System.out.println("DIAG_BQ_AUTH=FAIL");
            System.out.println("DIAG_AUTH_ERROR=" + msg);
            boolean jwtFailure = msg.contains("invalid_grant")
                    || msg.contains("Invalid JWT Signature")
                    || msg.contains("invalid or expired service account key");
            System.out.println("DIAG_NEEDS_FRESH_SA_JSON=" + (jwtFailure ? "YES" : "MAYBE"));
        }
    }

    private static String classifyFormat(String stored) {
        if (stored == null || stored.isBlank()) return "EMPTY";
        String trimmed = stored.trim();
        if (trimmed.startsWith(ConnectorCredentialCipher.ENVELOPE_PREFIX)) return "ENC:v1";
        if (trimmed.startsWith("{")) return "PLAINTEXT_JSON";
        return "OTHER";
    }

    private static String extractServiceAccountJson(JsonNode sa) throws Exception {
        if (sa == null || sa.isNull() || sa.isMissingNode()) return "";
        if (sa.isTextual()) return sa.asText().trim();
        if (sa.isObject()) return MAPPER.writeValueAsString(sa);
        return sa.asText("").trim();
    }

    private static Properties loadDatasourceProps() throws Exception {
        Properties props = new Properties();
        // Local overrides first (real secrets); then classpath base for non-secret defaults.
        Path local = Path.of("src/main/resources/application-local.properties");
        if (Files.isRegularFile(local)) {
            try (InputStream in = Files.newInputStream(local)) {
                props.load(in);
            }
        }
        try (InputStream in = BigQueryStoredCredentialAuthDiagnosticTest.class.getClassLoader()
                .getResourceAsStream("application.properties")) {
            if (in != null) {
                Properties base = new Properties();
                base.load(in);
                for (String name : base.stringPropertyNames()) {
                    props.putIfAbsent(name, base.getProperty(name));
                }
            }
        }
        return props;
    }

    private static String resolvedEncryptionKey(Properties props) {
        String fromEnv = System.getenv("KONTEXA_CONNECTOR_ENCRYPTION_KEY");
        if (usableKeyMaterial(fromEnv)) return fromEnv.trim();
        Path local = Path.of("src/main/resources/application-local.properties");
        if (Files.isRegularFile(local)) {
            try {
                Properties localProps = new Properties();
                try (InputStream in = Files.newInputStream(local)) {
                    localProps.load(in);
                }
                String fromLocal = localProps.getProperty("kontexa.connector.encryption-key");
                if (usableKeyMaterial(fromLocal)) return fromLocal.trim();
            } catch (Exception ignored) {
                // treat as unset
            }
        }
        String fromProps = props.getProperty("kontexa.connector.encryption-key");
        if (usableKeyMaterial(fromProps)) return fromProps.trim();
        return null;
    }

    private static boolean usableKeyMaterial(String raw) {
        if (raw == null || raw.isBlank()) return false;
        String t = raw.trim();
        // Unresolved Spring placeholder must not be fed to the cipher.
        if (t.contains("${")) return false;
        return true;
    }

    private static String rootCauseMessage(Throwable ex) {
        Throwable cur = ex;
        while (cur.getCause() != null && cur.getCause() != cur) {
            cur = cur.getCause();
        }
        String leaf = cur.getMessage() == null ? "" : cur.getMessage();
        String top = ex.getMessage() == null ? "" : ex.getMessage();
        if (top.contains("invalid_grant") || top.contains("Invalid JWT")
                || top.contains("invalid or expired service account")) {
            return top;
        }
        if (leaf.contains("invalid_grant") || leaf.contains("Invalid JWT")) {
            return leaf;
        }
        return top.isBlank() ? leaf : top;
    }

    /** Strip anything that looks like JSON / PEM / key material from error text. */
    private static String sanitize(String msg) {
        if (msg == null) return "";
        String s = msg.replace('\n', ' ').replace('\r', ' ');
        s = s.replaceAll("-----BEGIN[^-]+-----.*?-----END[^-]+-----", "[PEM_REDACTED]");
        s = s.replaceAll("\\{[^{}]{20,}\\}", "[JSON_REDACTED]");
        if (s.length() > 300) s = s.substring(0, 300) + "...";
        return s.trim();
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }

    private static String firstNonBlank(String a, String b) {
        if (a != null && !a.isBlank()) return a.trim();
        if (b != null && !b.isBlank()) return b.trim();
        return null;
    }
}
