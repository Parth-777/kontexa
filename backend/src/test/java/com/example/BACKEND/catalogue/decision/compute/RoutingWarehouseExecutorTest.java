package com.example.BACKEND.catalogue.decision.compute;

import com.example.BACKEND.catalogue.decision.contracts.DecisionModels.ComputationResultSet;
import com.example.BACKEND.catalogue.decision.contracts.DecisionModels.MetricPackExecutionPlan;
import com.example.BACKEND.catalogue.decision.contracts.DecisionModels.QuerySpec;
import com.example.BACKEND.tenant.TenantCloudConnectionService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RoutingWarehouseExecutorTest {

    @Mock BigQueryWarehouseExecutor bigQueryExecutor;
    @Mock SnowflakeWarehouseExecutor snowflakeExecutor;
    @Mock TenantCloudConnectionService connections;

    @Test
    void routesBigQueryProviderToBigQueryExecutor() {
        MetricPackExecutionPlan plan = plan("tenant-a");
        ComputationResultSet expected = emptyResult();
        when(connections.getProvider("tenant-a")).thenReturn("bigquery");
        when(bigQueryExecutor.execute(plan, "tenant-a")).thenReturn(expected);

        RoutingWarehouseExecutor routing = new RoutingWarehouseExecutor(
                bigQueryExecutor, snowflakeExecutor, connections);

        assertSame(expected, routing.execute(plan, "tenant-a"));
        verify(bigQueryExecutor).execute(eq(plan), eq("tenant-a"));
        verifyNoInteractions(snowflakeExecutor);
    }

    @Test
    void routesSnowflakeProviderToSnowflakeExecutor() {
        MetricPackExecutionPlan plan = plan("tenant-a");
        ComputationResultSet expected = emptyResult();
        when(connections.getProvider("tenant-a")).thenReturn("snowflake");
        when(snowflakeExecutor.execute(plan, "tenant-a")).thenReturn(expected);

        RoutingWarehouseExecutor routing = new RoutingWarehouseExecutor(
                bigQueryExecutor, snowflakeExecutor, connections);

        assertSame(expected, routing.execute(plan, "tenant-a"));
        verify(snowflakeExecutor).execute(eq(plan), eq("tenant-a"));
        verifyNoInteractions(bigQueryExecutor);
    }

    @Test
    void blankProviderPreservesLegacyBigQueryPath() {
        MetricPackExecutionPlan plan = plan("tenant-a");
        ComputationResultSet expected = emptyResult();
        when(connections.getProvider("tenant-a")).thenReturn("");
        when(bigQueryExecutor.execute(plan, "tenant-a")).thenReturn(expected);

        RoutingWarehouseExecutor routing = new RoutingWarehouseExecutor(
                bigQueryExecutor, snowflakeExecutor, connections);

        assertSame(expected, routing.execute(plan, "tenant-a"));
        verify(bigQueryExecutor).execute(plan, "tenant-a");
        verifyNoInteractions(snowflakeExecutor);
    }

    @Test
    void unsupportedProviderFailsClosed() {
        MetricPackExecutionPlan plan = plan("tenant-a");
        when(connections.getProvider("tenant-a")).thenReturn("postgresql");

        RoutingWarehouseExecutor routing = new RoutingWarehouseExecutor(
                bigQueryExecutor, snowflakeExecutor, connections);

        assertThrows(ComputeException.class, () -> routing.execute(plan, "tenant-a"));
        verifyNoInteractions(bigQueryExecutor);
        verifyNoInteractions(snowflakeExecutor);
    }

    private static MetricPackExecutionPlan plan(String tenantId) {
        return new MetricPackExecutionPlan("pack", "v1", tenantId, List.of(
                new QuerySpec("q1", "SELECT 1", Map.of())));
    }

    private static ComputationResultSet emptyResult() {
        return new ComputationResultSet(UUID.randomUUID(), List.of(), Map.of());
    }
}
