package com.example.BACKEND.catalogue.decision.transforms;

import com.example.BACKEND.tenant.TenantCloudConnectionService;
import com.example.BACKEND.tenancy.TenantContext;
import com.example.BACKEND.tenancy.TenantContextHolder;
import com.example.BACKEND.tenancy.TenantRole;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RoutingWarehouseDialectTest {

    @Mock TenantCloudConnectionService connections;

    @AfterEach
    void clear() {
        TenantContextHolder.clear();
    }

    @Test
    void snowflakeProviderSelectsSnowflakeFragments() {
        UUID tenantId = UUID.randomUUID();
        when(connections.getProvider(tenantId)).thenReturn("snowflake");
        TenantContextHolder.set(new TenantContext(
                tenantId, "acme", UUID.randomUUID(), "a@acme.com", "Ada",
                TenantRole.ANALYST, UUID.randomUUID(), "PASSWORD"));

        RoutingWarehouseDialect routing = new RoutingWarehouseDialect(
                new BigQueryDialect(), new SnowflakeDialect(), connections);

        assertEquals("DATE_TRUNC('MONTH', ts)", routing.timestampTrunc("ts", "MONTH"));
        assertEquals("DIV0NULL(a, b)", routing.safeDivide("a", "b"));
    }

    @Test
    void missingContextFallsBackToBigQuery() {
        RoutingWarehouseDialect routing = new RoutingWarehouseDialect(
                new BigQueryDialect(), new SnowflakeDialect(), connections);
        assertTrue(routing.timestampTrunc("ts", "MONTH").startsWith("TIMESTAMP_TRUNC"));
    }
}
