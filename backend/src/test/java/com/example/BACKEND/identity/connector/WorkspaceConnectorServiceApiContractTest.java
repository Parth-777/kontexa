package com.example.BACKEND.identity.connector;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.example.BACKEND.identity.IdentityRepository;
import com.example.BACKEND.identity.audit.AuditService;
import com.example.BACKEND.tenant.TenantAuthService;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.verify;

/**
 * API surface unchanged: callers still pass plaintext JSON into saveConnector;
 * sealing is internal to persistence.
 */
@ExtendWith(MockitoExtension.class)
class WorkspaceConnectorServiceApiContractTest {

    @Mock IdentityRepository identities;
    @Mock TenantAuthService legacyTenantAuth;
    @Mock AuditService audit;

    @Test
    void saveConnector_passesPlaintextToRepository_apiUnchanged() {
        WorkspaceConnectorService service = new WorkspaceConnectorService(
                identities, legacyTenantAuth, audit, new ObjectMapper());
        UUID workspaceId = UUID.randomUUID();
        String json = "{\"provider\":\"bigquery\",\"projectId\":\"p\"}";

        service.saveConnector(workspaceId, "BIGQUERY", json, "127.0.0.1", "junit");

        ArgumentCaptor<String> jsonCaptor = ArgumentCaptor.forClass(String.class);
        verify(identities).upsertConnector(org.mockito.ArgumentMatchers.eq(workspaceId),
                org.mockito.ArgumentMatchers.eq("BIGQUERY"), jsonCaptor.capture());
        assertEquals(json, jsonCaptor.getValue());
        verify(audit).log(org.mockito.ArgumentMatchers.eq(workspaceId),
                org.mockito.ArgumentMatchers.isNull(),
                org.mockito.ArgumentMatchers.eq(AuditService.CONNECTOR_CHANGE),
                org.mockito.ArgumentMatchers.eq("warehouse=BIGQUERY"),
                org.mockito.ArgumentMatchers.eq("127.0.0.1"),
                org.mockito.ArgumentMatchers.eq("junit"));
    }
}
