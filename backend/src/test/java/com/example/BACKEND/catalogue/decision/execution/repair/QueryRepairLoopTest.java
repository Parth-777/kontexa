package com.example.BACKEND.catalogue.decision.execution.repair;

import com.example.BACKEND.catalogue.decision.compute.WarehouseExecutor;
import com.example.BACKEND.catalogue.decision.contracts.DecisionModels.ComputationResultSet;
import com.example.BACKEND.catalogue.decision.contracts.DecisionModels.MetricPackExecutionPlan;
import com.example.BACKEND.catalogue.decision.contracts.DecisionModels.QueryResult;
import com.example.BACKEND.catalogue.decision.contracts.DecisionModels.QuerySpec;
import com.example.BACKEND.catalogue.decision.execution.sqltemplates.AnalyticalIntentKind;
import com.example.BACKEND.catalogue.decision.execution.sqltemplates.QueryExecutionDebugger;
import com.example.BACKEND.catalogue.decision.execution.sqltemplates.TemplateContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class QueryRepairLoopTest {

    private WarehouseExecutor warehouse;
    private QueryRepairLoop repairLoop;

    @BeforeEach
    void setUp() {
        warehouse = mock(WarehouseExecutor.class);
        repairLoop = new QueryRepairLoop(
                warehouse,
                new ZeroRowRecoveryEngine(),
                new IntermediateResultInspector(),
                new ResultQualityValidator(new IntermediateResultInspector()),
                new QueryExecutionDebugger());
    }

    @Test
    void zeroRows_triggersRepairWithRawDimension() {
        TemplateContext ctx = new TemplateContext(
                "How does trip distance contribute to revenue?",
                AnalyticalIntentKind.CONTRIBUTION, "yellow_taxi_trips",
                "total_amount", "trip_distance",
                "CASE WHEN trip_distance < 1 THEN '0-1' END", "trip_distance_bucket", "primary");

        String bucketSql = """
                SELECT CASE WHEN trip_distance < 1 THEN '0-1' END AS trip_distance_bucket,
                SUM(total_amount) AS revenue FROM yellow_taxi_trips GROUP BY trip_distance_bucket""";

        when(warehouse.execute(any(MetricPackExecutionPlan.class), eq("tenant")))
                .thenAnswer(inv -> {
                    String sql = inv.<MetricPackExecutionPlan>getArgument(0)
                            .querySpecs().getFirst().sql();
                    if (sql.contains("CASE WHEN")) {
                        return emptyResult("fail");
                    }
                    return groupedResult("raw", List.of(
                            row("trip_distance", "1.5", 100),
                            row("trip_distance", "3.2", 200)));
                });

        RepairOutcome outcome = repairLoop.repair(
                new QuerySpec("tpl__contribution__primary", bucketSql, Map.of()),
                ctx, "tenant");

        assertTrue(outcome.repaired());
        assertEquals(2, outcome.result().rows().size());
        assertTrue(outcome.attempts().size() >= 2);
        assertNotEquals("primary", outcome.winningStrategy());
    }

    @Test
    void degenerateSingleGroup_retriesUntilMultipleGroups() {
        TemplateContext ctx = new TemplateContext(
                "Revenue by trip distance", AnalyticalIntentKind.DISTRIBUTION,
                "yellow_taxi_trips", "total_amount", "trip_distance",
                "trip_distance", "trip_distance", "primary");

        when(warehouse.execute(any(MetricPackExecutionPlan.class), eq("tenant")))
                .thenAnswer(inv -> {
                    String sql = inv.<MetricPackExecutionPlan>getArgument(0)
                            .querySpecs().getFirst().sql();
                    if (!sql.contains("ROUND(")) {
                        return groupedResult("one", List.of(row("trip_distance", "5", 500)));
                    }
                    return groupedResult("two", List.of(
                            row("rounded_trip_distance", "1", 100),
                            row("rounded_trip_distance", "2", 200),
                            row("rounded_trip_distance", "5", 300)));
                });

        RepairOutcome outcome = repairLoop.repair(
                new QuerySpec("tpl__distribution__primary",
                        "SELECT trip_distance, SUM(total_amount) AS revenue FROM yellow_taxi_trips GROUP BY trip_distance",
                        Map.of()),
                ctx, "tenant");

        assertTrue(outcome.result().rows().size() >= 2);
    }

    private ComputationResultSet emptyResult(String key) {
        return new ComputationResultSet(
                java.util.UUID.randomUUID(),
                List.of(new QueryResult(key, List.of(), 5)),
                Map.of());
    }

    private ComputationResultSet groupedResult(String key, List<Map<String, Object>> rows) {
        return new ComputationResultSet(
                java.util.UUID.randomUUID(),
                List.of(new QueryResult(key, rows, 12)),
                Map.of());
    }

    private Map<String, Object> row(String dimKey, String dimVal, double revenue) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put(dimKey, dimVal);
        m.put("revenue", revenue);
        return m;
    }
}
