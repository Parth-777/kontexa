package com.example.BACKEND.catalogue;

import com.example.BACKEND.catalogue.model.CatalogueResult;
import com.example.BACKEND.catalogue.model.ColumnInfo;
import com.example.BACKEND.catalogue.model.TableInfo;
import com.example.BACKEND.catalogue.service.BigQueryCatalogueService;
import com.example.BACKEND.catalogue.service.CatalogueApprovalService;
import com.example.BACKEND.catalogue.service.CatalogueEnrichmentService;
import com.example.BACKEND.catalogue.service.CatalogueStorageService;
import com.example.BACKEND.catalogue.service.DataSamplerService;
import com.example.BACKEND.catalogue.service.PostgresCatalogueService;
import com.example.BACKEND.catalogue.service.SchemaDiscoveryService;
import com.example.BACKEND.catalogue.service.SnowflakeCatalogueService;
import com.example.BACKEND.identity.WorkspaceIdentityDomain.Role;
import com.example.BACKEND.identity.auth.AuthContext;
import com.example.BACKEND.identity.auth.AuthContextHolder;
import com.example.BACKEND.identity.auth.AuthorizationService;
import com.example.BACKEND.tenant.TenantCloudConnectionService;
import com.example.BACKEND.tenant.TenantCloudConnectionService.PostgresConfig;
import com.example.BACKEND.tenant.TenantContextResolver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CatalogueControllerPostgresqlRoutingTest {

    @Mock SchemaDiscoveryService schemaDiscovery;
    @Mock DataSamplerService dataSampler;
    @Mock CatalogueEnrichmentService enrichmentService;
    @Mock CatalogueStorageService storageService;
    @Mock CatalogueApprovalService approvalService;
    @Mock TenantCloudConnectionService cloudConnectionService;
    @Mock BigQueryCatalogueService bigQueryCatalogueService;
    @Mock SnowflakeCatalogueService snowflakeCatalogueService;
    @Mock PostgresCatalogueService postgresCatalogueService;
    @Mock TenantContextResolver tenantContextResolver;

    private CatalogueController controller;

    @BeforeEach
    void setUp() {
        AuthContextHolder.set(new AuthContext(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                "acme", "user@acme.test", "User", Role.ADMIN, "test"));
        controller = new CatalogueController(
                schemaDiscovery, dataSampler, enrichmentService, storageService, approvalService,
                cloudConnectionService, bigQueryCatalogueService, snowflakeCatalogueService,
                postgresCatalogueService, tenantContextResolver, new AuthorizationService());
    }

    @AfterEach
    void tearDown() {
        AuthContextHolder.clear();
    }

    @Test
    void buildFullRoutesPostgresqlToPostgresDiscoveryThenCommonEnrichment() {
        PostgresConfig config = new PostgresConfig(
                "acme", "db.example.com", 5432, "analytics", "public",
                "user", "secret", false, "postgresql://db.example.com");
        CatalogueResult discovered = postgresShapedResult();

        when(tenantContextResolver.resolveClientId(any(), any(Map.class))).thenReturn("acme");
        when(cloudConnectionService.getProvider("acme")).thenReturn("postgresql");
        when(cloudConnectionService.getPostgresConfig("acme")).thenReturn(Optional.of(config));
        when(postgresCatalogueService.discover(config, true)).thenReturn(discovered);

        ResponseEntity<CatalogueResult> response = controller.buildFull(null, Map.of("clientId", "acme"));

        assertSame(discovered, response.getBody());
        assertEquals("DRAFT", discovered.getStatus());
        verify(postgresCatalogueService).discover(config, true);
        verify(enrichmentService).enrich(discovered);
        verify(bigQueryCatalogueService, never()).discover(any(), anyBoolean());
        verify(snowflakeCatalogueService, never()).discover(any(), anyBoolean());
        verify(schemaDiscovery, never()).discover(anyString());
        verify(dataSampler, never()).sample(any());
    }

    @Test
    void localPostgresProviderDoesNotUseCustomerPostgresCatalogueService() {
        CatalogueResult local = new CatalogueResult("admindb", "acme");
        when(tenantContextResolver.resolveClientId(any(), any(Map.class))).thenReturn("acme");
        when(cloudConnectionService.getProvider("acme")).thenReturn("postgres");
        when(schemaDiscovery.discover("acme")).thenReturn(local);

        ResponseEntity<CatalogueResult> response = controller.buildFull(null, Map.of("clientId", "acme"));

        assertSame(local, response.getBody());
        verify(postgresCatalogueService, never()).discover(any(), anyBoolean());
        verify(schemaDiscovery).discover("acme");
        verify(enrichmentService).enrich(local);
    }

    private static CatalogueResult postgresShapedResult() {
        CatalogueResult result = new CatalogueResult("analytics", "public");
        TableInfo table = new TableInfo("event_log", "public");
        table.addColumn(new ColumnInfo("event_id", "bigint", false));
        table.addColumn(new ColumnInfo("metric_value", "numeric", true));
        result.addTable(table);
        return result;
    }
}
