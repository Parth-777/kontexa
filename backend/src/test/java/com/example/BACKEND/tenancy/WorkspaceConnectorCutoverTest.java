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

        when(workspaceConnectors.getConnectorJson(workspaceId)).thenReturn(Optional.of(json));

        TenantCloudConnectionService service = new TenantCloudConnectionService(
                workspaceConnectors, identities, new ObjectMapper());

        var cfg = service.getBigQueryConfig(workspaceId);
        assertTrue(cfg.isPresent());
        assertEquals("p", cfg.get().projectId());
        verify(workspaceConnectors).getConnectorJson(workspaceId);
    }

    @Test
    void cloudConnectionSlugPathUsesWorkspaceThenLegacy() {
        UUID workspaceId = UUID.randomUUID();
        String json = "{\"provider\":\"snowflake\",\"account\":\"a\",\"warehouse\":\"w\",\"database\":\"db\",\"schema\":\"PUBLIC\",\"username\":\"u\",\"password\":\"p\"}";

        when(identities.findWorkspaceIdBySlug("acme")).thenReturn(Optional.of(workspaceId));
        when(workspaceConnectors.getConnectorJson(workspaceId)).thenReturn(Optional.of(json));

        TenantCloudConnectionService service = new TenantCloudConnectionService(
                workspaceConnectors, identities, new ObjectMapper());

        var cfg = service.getSnowflakeConfig("acme");
        assertTrue(cfg.isPresent());
        assertEquals("a", cfg.get().account());
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
}
