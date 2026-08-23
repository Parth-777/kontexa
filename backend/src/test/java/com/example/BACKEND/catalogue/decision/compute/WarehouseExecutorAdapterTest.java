package com.example.BACKEND.catalogue.decision.compute;

import com.example.BACKEND.catalogue.decision.contracts.DecisionModels.ComputationResultSet;
import com.example.BACKEND.catalogue.decision.contracts.DecisionModels.MetricPackExecutionPlan;
import com.example.BACKEND.catalogue.decision.contracts.DecisionModels.QueryResult;
import com.example.BACKEND.catalogue.decision.contracts.DecisionModels.QuerySpec;
import com.example.BACKEND.catalogue.decision.execution.sqltemplates.QueryExecutionDebugger;
import com.example.BACKEND.tenant.BigQueryConnectorService;
import com.example.BACKEND.tenant.SnowflakeConnectorService;
import com.example.BACKEND.tenant.TenantCloudConnectionService;
import com.example.BACKEND.tenant.TenantCloudConnectionService.BigQueryConfig;
import com.example.BACKEND.tenant.TenantCloudConnectionService.SnowflakeConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WarehouseExecutorAdapterTest {

    @Mock SnowflakeConnectorService snowflakeConnector;
    @Mock BigQueryConnectorService bigQueryConnector;
    @Mock TenantCloudConnectionService connections;

    @Test
    void snowflakeExecutorUsesExistingExecuteSelect() {
        SnowflakeConfig cfg = new SnowflakeConfig(
                "tenant-a", "xy12345", "COMPUTE_WH", "ANALYTICS", "PUBLIC",
                "user", "secret", "snowflake://xy12345");
        when(connections.getSnowflakeConfig("tenant-a")).thenReturn(Optional.of(cfg));
        when(snowflakeConnector.executeSelect(
                eq("xy12345"), eq("COMPUTE_WH"), eq("ANALYTICS"), eq("PUBLIC"),
                eq("user"), eq("secret"), anyString()
        )).thenReturn(List.of(Map.of("n", 1)));

        SnowflakeWarehouseExecutor executor = new SnowflakeWarehouseExecutor(
                snowflakeConnector, connections, new QueryExecutionDebugger());
        MetricPackExecutionPlan plan = new MetricPackExecutionPlan(
                "pack", "v1", "tenant-a",
                List.of(new QuerySpec("q1", "SELECT COUNT(*) AS n FROM trips", Map.of())));

        ComputationResultSet result = executor.execute(plan, "tenant-a");

        assertEquals(1, result.results().size());
        QueryResult qr = result.results().getFirst();
        assertEquals("q1", qr.key());
        assertEquals(1, qr.rows().size());
        assertEquals(1, qr.rows().getFirst().get("n"));
        verify(snowflakeConnector).executeSelect(
                "xy12345", "COMPUTE_WH", "ANALYTICS", "PUBLIC",
                "user", "secret", "SELECT COUNT(*) AS n FROM trips LIMIT 500");
    }

    @Test
    void bigQueryExecutorBehaviorUnchanged() {
        BigQueryConfig cfg = new BigQueryConfig(
                "tenant-a", "proj", "US", "dataset", "{}", "bq://proj");
        when(connections.getBigQueryConfig("tenant-a")).thenReturn(Optional.of(cfg));
        when(bigQueryConnector.executeSelect(
                eq("proj"), eq("{}"), eq("US"), eq("dataset"), anyString()
        )).thenReturn(List.of(Map.of("n", 2)));

        BigQueryWarehouseExecutor executor = new BigQueryWarehouseExecutor(
                bigQueryConnector, connections, new QueryExecutionDebugger());
        MetricPackExecutionPlan plan = new MetricPackExecutionPlan(
                "pack", "v1", "tenant-a",
                List.of(new QuerySpec("q1", "SELECT COUNT(*) AS n FROM trips", Map.of())));

        ComputationResultSet result = executor.execute(plan, "tenant-a");

        assertEquals(1, result.results().size());
        assertEquals(2, result.results().getFirst().rows().getFirst().get("n"));
        assertEquals(0, result.executionMeta().get("failed"));
        assertTrue(result.executionMeta().containsKey("succeeded"));
        verify(bigQueryConnector).executeSelect(
                "proj", "{}", "US", "dataset",
                "SELECT COUNT(*) AS n FROM trips LIMIT 500");
    }
}
