package com.example.BACKEND.catalogue.investigation;

import com.example.BACKEND.catalogue.decision.transforms.SnowflakeDialect;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EvidenceRequestSqlCompilerDialectTest {

    @Test
    void snowflakeDialectDoesNotEmitBigQueryOnlySql() {
        EvidenceRequestSqlCompiler compiler =
                new EvidenceRequestSqlCompiler(new SlicePredicateBuilder(), new SnowflakeDialect());
        InvestigationPlan plan = new InvestigationPlan(
                new InvestigationPlan.Phenomenon(
                        "coverage",
                        new InvestigationObjective.Measure("total_amount", "SUM", List.of())),
                List.of(),
                List.of(
                        share("sh", "total_amount", "customer_id"),
                        timeSeries("ts", "total_amount", "tpep_pickup_datetime", "MONTH"),
                        avg("a", "total_amount")
                ),
                0.9);

        var compiled = compiler.compile(plan, "yellow_taxi_trips");
        String sql = compiled.executableSpecs().stream()
                .map(spec -> spec.sql())
                .reduce("", (a, b) -> a + "\n" + b);

        assertFalse(sql.contains("SAFE_DIVIDE"));
        assertFalse(sql.contains("TIMESTAMP_TRUNC"));
        assertFalse(sql.contains("COUNTIF("));
        assertTrue(sql.contains("DIV0NULL"));
        assertTrue(sql.contains("DATE_TRUNC('MONTH'"));
    }

    @Test
    void defaultCompilerPreservesBigQueryFragments() {
        EvidenceRequestSqlCompiler compiler =
                new EvidenceRequestSqlCompiler(new SlicePredicateBuilder());
        InvestigationPlan plan = new InvestigationPlan(
                new InvestigationPlan.Phenomenon(
                        "coverage",
                        new InvestigationObjective.Measure("total_amount", "SUM", List.of())),
                List.of(),
                List.of(timeSeries("ts", "total_amount", "tpep_pickup_datetime", "MONTH")),
                0.9);

        String sql = compiler.compile(plan, "yellow_taxi_trips").executableSpecs().getFirst().sql();
        assertTrue(sql.contains("TIMESTAMP_TRUNC(tpep_pickup_datetime, MONTH)"));
    }

    private static InvestigationPlan.EvidenceRequest share(String id, String metric, String dimension) {
        return new InvestigationPlan.EvidenceRequest(
                id, "share", null,
                new InvestigationObjective.Measure(metric, "SUM", List.of()),
                dimension, null, null, null,
                new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode().put("limit", 10));
    }

    private static InvestigationPlan.EvidenceRequest timeSeries(
            String id, String metric, String timeColumn, String grain
    ) {
        return new InvestigationPlan.EvidenceRequest(
                id, "time_series", null,
                new InvestigationObjective.Measure(metric, "SUM", List.of()),
                null, null, null, null,
                new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode()
                        .put("time_column", timeColumn).put("time_grain", grain));
    }

    private static InvestigationPlan.EvidenceRequest avg(String id, String metric) {
        return new InvestigationPlan.EvidenceRequest(
                id, "slice_metric", null,
                new InvestigationObjective.Measure(metric, "AVG", List.of()),
                null, null, null, null, null);
    }
}
