package com.example.BACKEND.user;

import com.example.BACKEND.identity.auth.AuthorizationService;
import com.example.BACKEND.tenant.BigQueryConnectorService;
import com.example.BACKEND.tenant.PostgresConnectorService;
import com.example.BACKEND.tenant.SnowflakeConnectorService;
import com.example.BACKEND.tenant.TenantCloudConnectionService;
import com.example.BACKEND.tenant.TenantCloudConnectionService.PostgresConfig;
import com.example.BACKEND.tenant.TenantContextResolver;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UserAccessPostgresListingTest {

    @Mock UserAccessService userAccessService;
    @Mock TenantCloudConnectionService cloudConnectionService;
    @Mock BigQueryConnectorService bigQueryConnectorService;
    @Mock SnowflakeConnectorService snowflakeConnectorService;
    @Mock PostgresConnectorService postgresConnectorService;
    @Mock TenantContextResolver tenantContextResolver;
    @Mock AuthorizationService authorization;

    @InjectMocks UserAccessController controller;

    @Test
    void postgresqlProviderListsCustomerTablesNotLocalAdmindb() {
        PostgresConfig config = new PostgresConfig(
                "acme", "db.example.com", 5432, "analytics", "public",
                "user", "secret", false, "postgresql://db.example.com");
        when(tenantContextResolver.resolveClientId(null, "acme")).thenReturn("acme");
        when(cloudConnectionService.getProvider("acme")).thenReturn("postgresql");
        when(cloudConnectionService.getPostgresConfig("acme")).thenReturn(Optional.of(config));
        when(postgresConnectorService.listTables(
                "db.example.com", 5432, "analytics", "public", "user", "secret", false))
                .thenReturn(List.of("event_log"));

        ResponseEntity<?> response = controller.listTables(null, "acme");

        assertEquals(HttpStatus.OK, response.getStatusCode());
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) response.getBody();
        assertEquals("postgresql", body.get("source"));
        assertEquals(List.of("event_log"), body.get("tables"));
        verify(userAccessService, never()).listTablesForSchema(anyString());
    }

    @Test
    void postgresqlProviderDoesNotFallThroughToLocalWhenConfigMissing() {
        when(tenantContextResolver.resolveClientId(null, "acme")).thenReturn("acme");
        when(cloudConnectionService.getProvider("acme")).thenReturn("postgresql");
        when(cloudConnectionService.getPostgresConfig("acme")).thenReturn(Optional.empty());

        ResponseEntity<?> response = controller.listTables(null, "acme");

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        verify(userAccessService, never()).listTablesForSchema(anyString());
        verify(postgresConnectorService, never()).listTables(
                anyString(), org.mockito.ArgumentMatchers.any(), anyString(), anyString(),
                anyString(), anyString(), org.mockito.ArgumentMatchers.anyBoolean());
    }
}
