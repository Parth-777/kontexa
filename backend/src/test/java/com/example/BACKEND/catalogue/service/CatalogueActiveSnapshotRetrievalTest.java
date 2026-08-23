package com.example.BACKEND.catalogue.service;

import com.example.BACKEND.catalogue.agent.StarSchemaDetector;
import com.example.BACKEND.catalogue.agent.scale.MetricRollupService;
import com.example.BACKEND.catalogue.agent.scale.ScaleProperties;
import com.example.BACKEND.catalogue.entity.CatalogueColumnEntity;
import com.example.BACKEND.catalogue.entity.CatalogueSnapshotEntity;
import com.example.BACKEND.catalogue.entity.CatalogueTableEntity;
import com.example.BACKEND.catalogue.entity.ClientCatalogueEntity;
import com.example.BACKEND.catalogue.repository.CatalogueSnapshotRepository;
import com.example.BACKEND.catalogue.repository.ClientCatalogueRepository;
import com.example.BACKEND.tenancy.TenantContext;
import com.example.BACKEND.tenancy.TenantContextHolder;
import com.example.BACKEND.tenancy.TenantRole;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Field;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CatalogueActiveSnapshotRetrievalTest {

    @Mock ClientCatalogueRepository catalogueRepo;
    @Mock CatalogueSnapshotRepository snapshotRepo;
    @Mock CatalogueSemanticEnricher semanticEnricher;
    @Mock StarSchemaDetector starSchemaDetector;
    @Mock MetricRollupService metricRollupService;
    @Mock ScaleProperties scaleProperties;
    @Mock TenantBusinessProfileService tenantBusinessProfileService;

    private CatalogueApprovalService service;
    private final ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        service = new CatalogueApprovalService(
                catalogueRepo, snapshotRepo, mapper,
                semanticEnricher, starSchemaDetector, metricRollupService,
                scaleProperties, tenantBusinessProfileService);
    }

    @AfterEach
    void clear() {
        TenantContextHolder.clear();
    }

    @Test
    void staleSlugSnapshotIsIgnoredWhenNewerOrdersCatalogueIsActive() throws Exception {
        UUID tenantId = UUID.randomUUID();
        bindTenant(tenantId, "acme");
        ClientCatalogueEntity nyc = catalogue(1L, "acme", tenantId, "APPROVED",
                LocalDateTime.now().minusDays(2), "nyc_taxi", "nyc", "RatecodeID");
        ClientCatalogueEntity orders = catalogue(2L, "acme", tenantId, "APPROVED",
                LocalDateTime.now(), "ORDERS", "PUBLIC", "ORDER_ID");
        when(catalogueRepo.findByTenantId(tenantId)).thenReturn(List.of(nyc, orders));

        CatalogueSnapshotEntity nycSnap = snapshot(10L, 1L, "acme", nycTaxiJson());
        CatalogueSnapshotEntity ordersSnap = snapshot(11L, 2L, "acme-orders", ordersJson());
        when(snapshotRepo.findFirstByCatalogueIdOrderByIdDesc(2L)).thenReturn(Optional.of(ordersSnap));

        String json = service.getApprovedSnapshot("acme");
        assertTrue(json.contains("ORDERS"));
        assertTrue(json.contains("ORDER_ID"));
        assertFalse(json.contains("RatecodeID"));
        assertFalse(json.contains("nyc_taxi"));
        verify(snapshotRepo, never()).findByClientId("acme");
    }

    @Test
    void retrievalUsesCatalogueIdNotSlugWhenTwoSnapshotsExist() {
        UUID tenantId = UUID.randomUUID();
        bindTenant(tenantId, "acme");
        ClientCatalogueEntity active = catalogue(2L, "acme", tenantId, "APPROVED",
                LocalDateTime.now(), "ORDERS", "PUBLIC", "ORDER_ID");
        when(catalogueRepo.findByTenantId(tenantId)).thenReturn(List.of(active));

        CatalogueSnapshotEntity snapY = snapshot(10L, 1L, "acme", nycTaxiJson());
        CatalogueSnapshotEntity snapX = snapshot(11L, 2L, "acme", ordersJson());
        when(snapshotRepo.findFirstByCatalogueIdOrderByIdDesc(2L)).thenReturn(Optional.of(snapX));

        String json = service.getApprovedSnapshot("acme");
        assertEquals(ordersJson(), json);
        assertFalse(json.contains("RatecodeID"));
        verify(snapshotRepo, never()).findByClientId("acme");
    }

    @Test
    void newestApprovedCatalogueIsSelectedWhileOlderRemainsStored() {
        UUID tenantId = UUID.randomUUID();
        bindTenant(tenantId, "acme");
        ClientCatalogueEntity older = catalogue(1L, "acme", tenantId, "APPROVED",
                LocalDateTime.now().minusDays(1), "nyc_taxi", "nyc", "RatecodeID");
        ClientCatalogueEntity newer = catalogue(2L, "acme", tenantId, "APPROVED",
                LocalDateTime.now(), "ORDERS", "PUBLIC", "ORDER_ID");
        when(catalogueRepo.findByTenantId(tenantId)).thenReturn(List.of(older, newer));

        ClientCatalogueEntity selected = service.requireActiveApprovedCatalogue("acme");
        assertEquals(2L, selected.getId());
        assertEquals("APPROVED", older.getStatus());
        assertEquals("APPROVED", newer.getStatus());
    }

    @Test
    void singleBigQueryCatalogueSnapshotUnchanged() {
        ClientCatalogueEntity taxi = catalogue(5L, "bq-tenant", null, "APPROVED",
                LocalDateTime.now(), "yellow_taxi_trips", "public", "total_amount");
        when(catalogueRepo.findByClientId("bq-tenant")).thenReturn(List.of(taxi));
        String json = """
                {"tables":[{"tableName":"yellow_taxi_trips","tableSchema":"public",
                "columns":[{"columnName":"total_amount","role":"metric"}]}]}
                """;
        when(snapshotRepo.findFirstByCatalogueIdOrderByIdDesc(5L))
                .thenReturn(Optional.of(snapshot(3L, 5L, "bq-tenant", json)));

        String loaded = service.getApprovedSnapshot("bq-tenant");
        assertEquals(json, loaded);
        assertTrue(loaded.contains("yellow_taxi_trips"));
        assertTrue(loaded.contains("total_amount"));
    }

    @Test
    void missingOrdersSnapshotDoesNotReturnNycTaxiSnapshot() {
        UUID tenantId = UUID.randomUUID();
        bindTenant(tenantId, "acme");
        ClientCatalogueEntity orders = catalogue(2L, "acme", tenantId, "APPROVED",
                LocalDateTime.now(), "ORDERS", "PUBLIC", "ORDER_ID");
        when(catalogueRepo.findByTenantId(tenantId)).thenReturn(List.of(orders));
        when(snapshotRepo.findFirstByCatalogueIdOrderByIdDesc(2L)).thenReturn(Optional.empty());
        when(snapshotRepo.findByTenantId(tenantId))
                .thenReturn(Optional.of(snapshot(10L, 1L, "acme", nycTaxiJson())));

        String json = service.getApprovedSnapshot("acme");
        assertTrue(json.contains("ORDERS"));
        assertTrue(json.contains("ORDER_ID"));
        assertFalse(json.contains("RatecodeID"));
        assertFalse(json.contains("nyc_taxi"));
    }

    @Test
    void approvingCataloguePersistsSnapshotForThatCatalogueId() {
        ClientCatalogueEntity orders = catalogue(9L, "acme", UUID.randomUUID(), "DRAFT",
                LocalDateTime.now(), "ORDERS", "PUBLIC", "ORDER_ID");
        when(catalogueRepo.findById(9L)).thenReturn(Optional.of(orders));
        when(catalogueRepo.save(any(ClientCatalogueEntity.class))).thenAnswer(inv -> inv.getArgument(0));
        when(snapshotRepo.findFirstByCatalogueIdOrderByIdDesc(9L)).thenReturn(Optional.empty());
        when(snapshotRepo.findByTenantId(orders.getTenantId())).thenReturn(Optional.empty());
        when(snapshotRepo.findByClientId("acme")).thenReturn(Optional.empty());
        when(snapshotRepo.save(any(CatalogueSnapshotEntity.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        when(scaleProperties.isRollupEnabled()).thenReturn(false);

        service.approve(9L, "acme");

        ArgumentCaptor<CatalogueSnapshotEntity> captor =
                ArgumentCaptor.forClass(CatalogueSnapshotEntity.class);
        verify(snapshotRepo).save(captor.capture());
        CatalogueSnapshotEntity saved = captor.getValue();
        assertEquals(9L, saved.getCatalogueId());
        assertEquals("acme", saved.getClientId());
        assertTrue(saved.getCatalogueJson().contains("ORDERS"));
        assertFalse(saved.getCatalogueJson().contains("RatecodeID"));
    }

    @Test
    void approvingNewCatalogueReplacesExistingSnapshotSlot() {
        UUID tenantId = UUID.randomUUID();
        ClientCatalogueEntity orders = catalogue(33L, "google-admin", tenantId, "DRAFT",
                LocalDateTime.now(), "ORDERS", "PUBLIC", "ORDER_ID");
        CatalogueSnapshotEntity existing = snapshot(10L, 29L, "google-admin", nycTaxiJson());
        existing.setTenantId(tenantId);

        when(catalogueRepo.findById(33L)).thenReturn(Optional.of(orders));
        when(catalogueRepo.save(any(ClientCatalogueEntity.class))).thenAnswer(inv -> inv.getArgument(0));
        when(snapshotRepo.findFirstByCatalogueIdOrderByIdDesc(33L)).thenReturn(Optional.empty());
        when(snapshotRepo.findByTenantId(tenantId)).thenReturn(Optional.of(existing));
        when(snapshotRepo.save(any(CatalogueSnapshotEntity.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        when(scaleProperties.isRollupEnabled()).thenReturn(false);

        service.approve(33L, "google-admin");

        ArgumentCaptor<CatalogueSnapshotEntity> captor =
                ArgumentCaptor.forClass(CatalogueSnapshotEntity.class);
        verify(snapshotRepo).save(captor.capture());
        CatalogueSnapshotEntity saved = captor.getValue();
        assertEquals(10L, saved.getId());
        assertEquals(33L, saved.getCatalogueId());
        assertEquals("google-admin", saved.getClientId());
        assertEquals(tenantId, saved.getTenantId());
        assertTrue(saved.getCatalogueJson().contains("ORDERS"));
        assertTrue(saved.getCatalogueJson().contains("ORDER_ID"));
        assertFalse(saved.getCatalogueJson().contains("RatecodeID"));
        assertFalse(saved.getCatalogueJson().contains("nyc_taxi"));
        verify(snapshotRepo, never()).findByClientId(any());
    }

    private void bindTenant(UUID tenantId, String slug) {
        TenantContextHolder.set(new TenantContext(
                tenantId, slug, UUID.randomUUID(), "a@acme.com", "Ada",
                TenantRole.ANALYST, UUID.randomUUID(), "PASSWORD"));
    }

    private static ClientCatalogueEntity catalogue(
            long id, String clientId, UUID tenantId, String status, LocalDateTime updatedAt,
            String tableName, String schema, String columnName
    ) {
        ClientCatalogueEntity c = new ClientCatalogueEntity();
        setId(c, id);
        c.setClientId(clientId);
        c.setTenantId(tenantId);
        c.setStatus(status);
        c.setSchemaName(schema);
        c.setUpdatedAt(updatedAt);
        c.setCreatedAt(updatedAt);
        CatalogueTableEntity table = new CatalogueTableEntity();
        table.setTableName(tableName);
        table.setTableSchema(schema);
        table.setCatalogue(c);
        CatalogueColumnEntity col = new CatalogueColumnEntity();
        col.setColumnName(columnName);
        col.setDataType("NUMBER");
        col.setRole("metric");
        col.setCatalogueTable(table);
        table.getColumns().add(col);
        c.getTables().add(table);
        return c;
    }

    private static CatalogueSnapshotEntity snapshot(long id, long catalogueId, String clientId, String json) {
        CatalogueSnapshotEntity s = new CatalogueSnapshotEntity();
        setId(s, id);
        s.setCatalogueId(catalogueId);
        s.setClientId(clientId);
        s.setCatalogueJson(json);
        return s;
    }

    private static void setId(Object entity, long id) {
        try {
            Field field = entity.getClass().getDeclaredField("id");
            field.setAccessible(true);
            field.set(entity, id);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String nycTaxiJson() {
        return "{\"tables\":[{\"tableName\":\"nyc_taxi\",\"tableSchema\":\"nyc\","
                + "\"columns\":[{\"columnName\":\"RatecodeID\",\"role\":\"metric\"}]}]}";
    }

    private static String ordersJson() {
        return "{\"tables\":[{\"tableName\":\"ORDERS\",\"tableSchema\":\"PUBLIC\","
                + "\"columns\":[{\"columnName\":\"ORDER_ID\",\"role\":\"metric\"}]}]}";
    }
}
