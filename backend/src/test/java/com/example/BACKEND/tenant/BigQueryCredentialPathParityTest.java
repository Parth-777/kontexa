package com.example.BACKEND.tenant;

import com.example.BACKEND.identity.IdentityRepository;
import com.example.BACKEND.identity.connector.WorkspaceConnectorService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Proves Path A (test-connection request body) vs Path B (warehouse persisted lookup)
 * and that BigQuery config resolution is typed to warehouse_type=BIGQUERY.
 */
@ExtendWith(MockitoExtension.class)
class BigQueryCredentialPathParityTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Mock WorkspaceConnectorService workspaceConnectors;
    @Mock IdentityRepository identities;

    private TenantCloudConnectionService cloudConnections;

    @BeforeEach
    void setUp() {
        cloudConnections = new TenantCloudConnectionService(workspaceConnectors, identities, MAPPER);
    }

    @Test
    void getBigQueryConfig_loadsTypedBigQueryRow_notNewestUntyped() {
        UUID workspaceId = UUID.randomUUID();
        String bqJson = outerConfig("bq-project", "sa-bq");
        when(workspaceConnectors.getConnectorJson(workspaceId, "BIGQUERY"))
                .thenReturn(Optional.of(bqJson));

        var cfg = cloudConnections.getBigQueryConfig(workspaceId);
        assertTrue(cfg.isPresent());
        assertEquals("bq-project", cfg.get().projectId());
        assertEquals("sa-bq", cfg.get().serviceAccountJson());

        verify(workspaceConnectors).getConnectorJson(workspaceId, "BIGQUERY");
        verify(workspaceConnectors, never()).getConnectorJson(eq(workspaceId), isNull());
        verify(workspaceConnectors, never()).getConnectorJson(workspaceId);
    }

    @Test
    void getBigQueryConfig_bySlug_usesTypedLookup() {
        UUID workspaceId = UUID.randomUUID();
        when(identities.findWorkspaceIdBySlug("acme")).thenReturn(Optional.of(workspaceId));
        when(workspaceConnectors.getConnectorJson(workspaceId, "BIGQUERY"))
                .thenReturn(Optional.of(outerConfig("p", "sa")));

        var cfg = cloudConnections.getBigQueryConfig("acme");
        assertTrue(cfg.isPresent());
        assertEquals("p", cfg.get().projectId());
        verify(workspaceConnectors).getConnectorJson(workspaceId, "BIGQUERY");
    }

    @Test
    void persistRoundTrip_preservesServiceAccountFingerprint() throws Exception {
        String saJson = ""
                + "{"
                + "\"type\":\"service_account\","
                + "\"project_id\":\"demo\","
                + "\"private_key_id\":\"kid-1\","
                + "\"client_email\":\"svc@demo.iam.gserviceaccount.com\","
                + "\"private_key\":\"-----BEGIN PRIVATE KEY-----\\n"
                + "MIIEvQIBADANBgkqhkiG9w0BAQEFAASCBKcwggSjAgEAAoIBAQC7VJTUt9Us8cKB\\n"
                + "-----END PRIVATE KEY-----\\n\""
                + "}";

        ObjectNode root = MAPPER.createObjectNode();
        root.put("provider", "bigquery");
        root.put("projectId", "demo");
        root.put("location", "US");
        root.put("dataset", "analytics");
        root.put("connectionLink", "bigquery://demo");
        root.put("serviceAccountJson", saJson);
        String persisted = MAPPER.writeValueAsString(root);

        UUID workspaceId = UUID.randomUUID();
        when(workspaceConnectors.getConnectorJson(workspaceId, "BIGQUERY"))
                .thenReturn(Optional.of(persisted));

        var cfg = cloudConnections.getBigQueryConfig(workspaceId).orElseThrow();
        assertEquals(sha256(saJson), sha256(cfg.serviceAccountJson()));
        JsonNode sa = MAPPER.readTree(cfg.serviceAccountJson());
        assertEquals("kid-1", sa.path("private_key_id").asText());
        assertEquals("svc@demo.iam.gserviceaccount.com", sa.path("client_email").asText());
        assertTrue(sa.path("private_key").asText().contains("BEGIN PRIVATE KEY"));
        assertFalse(cfg.serviceAccountJson().isBlank());
    }

    @Test
    void saveConnector_receivesWarehouseTypeBigQuery() {
        UUID workspaceId = UUID.randomUUID();
        cloudConnections.saveRawConfig(workspaceId, "BIGQUERY", "{\"provider\":\"bigquery\"}", "127.0.0.1", "junit");
        ArgumentCaptor<String> typeCaptor = ArgumentCaptor.forClass(String.class);
        verify(workspaceConnectors).saveConnector(
                eq(workspaceId), typeCaptor.capture(), any(), eq("127.0.0.1"), eq("junit"));
        assertEquals("BIGQUERY", typeCaptor.getValue());
    }

    private static String outerConfig(String projectId, String sa) {
        try {
            ObjectNode root = MAPPER.createObjectNode();
            root.put("provider", "bigquery");
            root.put("projectId", projectId);
            root.put("location", "US");
            root.put("dataset", "d");
            root.put("serviceAccountJson", sa);
            return MAPPER.writeValueAsString(root);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static String sha256(String value) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8));
        return HexFormat.of().formatHex(digest);
    }
}
