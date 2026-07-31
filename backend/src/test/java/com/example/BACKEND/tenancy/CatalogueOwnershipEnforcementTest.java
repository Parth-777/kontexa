package com.example.BACKEND.tenancy;

import com.example.BACKEND.catalogue.entity.ClientCatalogueEntity;
import com.example.BACKEND.catalogue.repository.CatalogueSnapshotRepository;
import com.example.BACKEND.catalogue.repository.ClientCatalogueRepository;
import com.example.BACKEND.catalogue.service.CatalogueApprovalService;
import com.example.BACKEND.catalogue.service.CatalogueSemanticEnricher;
import com.example.BACKEND.catalogue.service.TenantBusinessProfileService;
import com.example.BACKEND.catalogue.agent.StarSchemaDetector;
import com.example.BACKEND.catalogue.agent.scale.MetricRollupService;
import com.example.BACKEND.catalogue.agent.scale.ScaleProperties;
import com.example.BACKEND.identity.auth.ForbiddenException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CatalogueOwnershipEnforcementTest {

    @Mock ClientCatalogueRepository catalogueRepo;
    @Mock CatalogueSnapshotRepository snapshotRepo;
    @Mock CatalogueSemanticEnricher semanticEnricher;
    @Mock StarSchemaDetector starSchemaDetector;
    @Mock MetricRollupService metricRollupService;
    @Mock ScaleProperties scaleProperties;
    @Mock TenantBusinessProfileService tenantBusinessProfileService;

    @AfterEach
    void clear() {
        TenantContextHolder.clear();
    }

    private CatalogueApprovalService service() {
        return new CatalogueApprovalService(
                catalogueRepo, snapshotRepo, new ObjectMapper(),
                semanticEnricher, starSchemaDetector, metricRollupService,
                scaleProperties, tenantBusinessProfileService);
    }

    @Test
    void requireOwnedRejectsCrossTenantCatalogueId() {
        ClientCatalogueEntity other = mock(ClientCatalogueEntity.class);
        when(other.getTenantId()).thenReturn(UUID.randomUUID());
        when(catalogueRepo.findById(42L)).thenReturn(Optional.of(other));

        TenantContextHolder.set(new TenantContext(
                UUID.randomUUID(), "acme", UUID.randomUUID(), "a@acme.com", "Ada",
                TenantRole.ANALYST, UUID.randomUUID(), "PASSWORD"));

        assertThrows(ForbiddenException.class, () -> service().requireOwned(42L, "acme"));
    }

    @Test
    void requireOwnedAllowsMatchingTenantUuid() {
        UUID tenantId = UUID.randomUUID();
        ClientCatalogueEntity owned = mock(ClientCatalogueEntity.class);
        when(owned.getId()).thenReturn(7L);
        when(owned.getTenantId()).thenReturn(tenantId);
        when(catalogueRepo.findById(7L)).thenReturn(Optional.of(owned));

        TenantContext ctx = new TenantContext(
                tenantId, "acme", UUID.randomUUID(), "a@acme.com", "Ada",
                TenantRole.ANALYST, UUID.randomUUID(), "PASSWORD");
        TenantContextHolder.set(ctx);

        ClientCatalogueEntity result = service().requireOwned(7L, ctx);
        assertEquals(7L, result.getId());
    }

    @Test
    void approveWithoutOwnerKeyFailsClosed() {
        assertThrows(ForbiddenException.class, () -> service().approve(1L, null));
        assertThrows(ForbiddenException.class, () -> service().approve(1L, "  "));
    }
}
