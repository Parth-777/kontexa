package com.example.BACKEND.tenancy;

import com.example.BACKEND.identity.IdentityRepository;
import com.example.BACKEND.identity.connector.WorkspaceConnectorService;
import com.example.BACKEND.tenant.TenantCloudConnectionService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WorkspaceConnectorCutoverTest {

    @Mock WorkspaceConnectorService workspaceConnectors;
    @Mock IdentityRepository identities;

    @Test
    void cloudConnectionPrefersWorkspaceConnectorsByUuid() {
        UUID workspaceId = UUID.randomUUID();
        String json = "{\"provider\":\"bigquery\",\"projectId\":\"p\",\"dataset\":\"d\",\"serviceAccountJson\":\"{}\"}";

        when(workspaceConnectors.getConnectorJson(workspaceId, "BIGQUERY")).thenReturn(Optional.of(json));

        TenantCloudConnectionService service = new TenantCloudConnectionService(
                workspaceConnectors, identities, new ObjectMapper());

        var cfg = service.getBigQueryConfig(workspaceId);
        assertTrue(cfg.isPresent());
        assertEquals("p", cfg.get().projectId());
        verify(workspaceConnectors).getConnectorJson(workspaceId, "BIGQUERY");
    }

    @Test
    void cloudConnectionSlugPathUsesWorkspaceThenLegacy() {
        UUID workspaceId = UUID.randomUUID();
        String json = "{\"provider\":\"snowflake\",\"account\":\"a\",\"warehouse\":\"w\",\"database\":\"db\",\"schema\":\"PUBLIC\",\"username\":\"u\",\"password\":\"p\"}";

        when(identities.findWorkspaceIdBySlug("acme")).thenReturn(Optional.of(workspaceId));
        when(workspaceConnectors.getConnectorJson(workspaceId, "SNOWFLAKE")).thenReturn(Optional.of(json));

        TenantCloudConnectionService service = new TenantCloudConnectionService(
                workspaceConnectors, identities, new ObjectMapper());

        var cfg = service.getSnowflakeConfig("acme");
        assertTrue(cfg.isPresent());
        assertEquals("a", cfg.get().account());
    }

    @Test
    void getProviderUsesMostRecentlyUpdatedConnector() {
        UUID workspaceId = UUID.randomUUID();
        String sfJson = "{\"provider\":\"snowflake\",\"account\":\"a\"}";

        when(workspaceConnectors.getConnectorJson(workspaceId, null)).thenReturn(Optional.of(sfJson));

        TenantCloudConnectionService service = new TenantCloudConnectionService(
                workspaceConnectors, identities, new ObjectMapper());

        assertEquals("snowflake", service.getProvider(workspaceId));
    }

    @Test
    void saveGoesToWorkspaceConnectors() {
        UUID workspaceId = UUID.randomUUID();
        TenantCloudConnectionService service = new TenantCloudConnectionService(
                workspaceConnectors, identities, new ObjectMapper());

        service.saveRawConfig(workspaceId, "BIGQUERY", "{\"provider\":\"bigquery\"}", "127.0.0.1", "test");
        verify(workspaceConnectors).saveConnector(
                workspaceId, "BIGQUERY", "{\"provider\":\"bigquery\"}", "127.0.0.1", "test");
    }

    @Test
    void getProviderReturnsPostgresqlFromNewestConnector() {
        UUID workspaceId = UUID.randomUUID();
        String json = "{\"provider\":\"postgresql\",\"host\":\"db.example.com\"}";
        when(workspaceConnectors.getConnectorJson(workspaceId, null)).thenReturn(Optional.of(json));

        TenantCloudConnectionService service = new TenantCloudConnectionService(
                workspaceConnectors, identities, new ObjectMapper());

        assertEquals("postgresql", service.getProvider(workspaceId));
    }

    @Test
    void parsePostgresConfigRequiresPostgresqlProviderNotPostgres() {
        UUID workspaceId = UUID.randomUUID();
        String localFallback = "{\"provider\":\"postgres\",\"host\":\"localhost\",\"database\":\"admindb\"}";
        String warehouse = "{\"provider\":\"postgresql\",\"host\":\"db.example.com\",\"port\":5432,"
                + "\"database\":\"analytics\",\"schema\":\"public\",\"username\":\"u\",\"password\":\"p\",\"ssl\":true}";

        when(workspaceConnectors.getConnectorJson(workspaceId, "POSTGRESQL"))
                .thenReturn(Optional.of(localFallback));
        TenantCloudConnectionService rejected = new TenantCloudConnectionService(
                workspaceConnectors, identities, new ObjectMapper());
        assertTrue(rejected.getPostgresConfig(workspaceId).isEmpty());

        when(workspaceConnectors.getConnectorJson(workspaceId, "POSTGRESQL"))
                .thenReturn(Optional.of(warehouse));
        TenantCloudConnectionService accepted = new TenantCloudConnectionService(
                workspaceConnectors, identities, new ObjectMapper());
        var cfg = accepted.getPostgresConfig(workspaceId);
        assertTrue(cfg.isPresent());
        assertEquals("db.example.com", cfg.get().host());
        assertEquals(5432, cfg.get().port());
        assertEquals("analytics", cfg.get().database());
        assertEquals("public", cfg.get().schema());
        assertEquals("u", cfg.get().username());
        assertEquals("p", cfg.get().password());
        assertTrue(cfg.get().ssl());
    }

    @Test
    void postgresConfigDefaultsPortAndSchema() {
        UUID workspaceId = UUID.randomUUID();
        String json = "{\"provider\":\"postgresql\",\"host\":\"db.example.com\",\"database\":\"analytics\","
                + "\"username\":\"u\",\"password\":\"p\"}";
        when(workspaceConnectors.getConnectorJson(workspaceId, "POSTGRESQL")).thenReturn(Optional.of(json));

        TenantCloudConnectionService service = new TenantCloudConnectionService(
                workspaceConnectors, identities, new ObjectMapper());
        var cfg = service.getPostgresConfig(workspaceId);
        assertTrue(cfg.isPresent());
        assertEquals(5432, cfg.get().port());
        assertEquals("public", cfg.get().schema());
        assertEquals(false, cfg.get().ssl());
    }

    @Test
    void savePostgresGoesToWorkspaceConnectorsWithPostgresqlWarehouseType() {
        UUID workspaceId = UUID.randomUUID();
        TenantCloudConnectionService service = new TenantCloudConnectionService(
                workspaceConnectors, identities, new ObjectMapper());
        String json = "{\"provider\":\"postgresql\",\"host\":\"db.example.com\"}";

        service.saveRawConfig(workspaceId, "POSTGRESQL", json, "127.0.0.1", "test");
        verify(workspaceConnectors).saveConnector(workspaceId, "POSTGRESQL", json, "127.0.0.1", "test");
    }
}
