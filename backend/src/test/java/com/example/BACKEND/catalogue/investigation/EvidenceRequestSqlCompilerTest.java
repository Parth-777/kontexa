package com.example.BACKEND.catalogue.investigation;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EvidenceRequestSqlCompilerTest {

    private final EvidenceRequestSqlCompiler compiler =
            new EvidenceRequestSqlCompiler(new SlicePredicateBuilder());

    @Test
    void compilesAllSupportedKinds() {
        InvestigationPlan plan = new InvestigationPlan(
                new InvestigationPlan.Phenomenon(
                        "coverage",
                        new InvestigationObjective.Measure("total_amount", "SUM", List.of())),
                List.of(),
                List.of(
                        req("s", "slice_metric", "total_amount", null, null),
                        req("m", "metric_by_dimension", "total_amount", "region", null),
                        req("d", "distribution", null, "region", null),
                        corr("c", "trip_distance", "total_amount"),
                        req("r", "rank", "total_amount", "customer_id", MapNode.limit(5)),
                        req("t", "top_n", "total_amount", "customer_id", MapNode.limit(5)),
                        req("b", "bottom_n", "total_amount", "customer_id", MapNode.limit(5)),
                        req("sh", "share", "total_amount", "customer_id", MapNode.limit(10)),
                        req("co", "concentration", "total_amount", "customer_id", MapNode.limit(10)),
                        timeSeries("ts", "total_amount", "tpep_pickup_datetime", "MONTH")
                ),
                0.9);

        var compiled = compiler.compile(plan, "yellow_taxi_trips");
        assertTrue(compiled.requests().stream().allMatch(EvidenceRequestSqlCompiler.CompiledRequest::ok),
                () -> compiled.requests().stream()
                        .filter(r -> !r.ok())
                        .map(r -> r.request().kind() + ":" + r.error())
                        .toList()
                        .toString());
        assertTrue(compiled.executableSpecs().stream()
                .anyMatch(s -> s.sql().contains("CORR(")));
        assertTrue(compiled.executableSpecs().stream()
                .anyMatch(s -> s.sql().contains("cumulative_share")));
        assertTrue(compiled.executableSpecs().stream()
                .anyMatch(s -> s.sql().contains("TIMESTAMP_TRUNC")));
    }

    @Test
    void failsClosedOnUnknownKind() {
        InvestigationPlan plan = new InvestigationPlan(
                new InvestigationPlan.Phenomenon("x", null),
                List.of(),
                List.of(req("bad", "funnel", null, null, null)),
                0.5);
        var compiled = compiler.compile(plan, "yellow_taxi_trips");
        assertFalse(compiled.requests().getFirst().ok());
        assertTrue(compiled.requests().getFirst().error().contains("unsupported"));
    }

    private static InvestigationPlan.EvidenceRequest req(
            String id, String kind, String metric, String dimension, com.fasterxml.jackson.databind.JsonNode params
    ) {
        InvestigationObjective.Measure measure = metric == null ? null
                : new InvestigationObjective.Measure(metric, "SUM", List.of());
        return new InvestigationPlan.EvidenceRequest(
                id, kind, null, measure, dimension, null, null, null, params);
    }

    private static InvestigationPlan.EvidenceRequest corr(String id, String x, String y) {
        return new InvestigationPlan.EvidenceRequest(
                id, "correlation", null,
                new InvestigationObjective.Measure(y, "SUM", List.of()),
                x, null, null, null, MapNode.xy(x, y));
    }

    private static InvestigationPlan.EvidenceRequest timeSeries(
            String id, String metric, String timeColumn, String grain
    ) {
        return new InvestigationPlan.EvidenceRequest(
                id, "time_series", null,
                new InvestigationObjective.Measure(metric, "SUM", List.of()),
                null, null, null, null, MapNode.time(timeColumn, grain));
    }

    private static final class MapNode {
        private static final com.fasterxml.jackson.databind.ObjectMapper M =
                new com.fasterxml.jackson.databind.ObjectMapper();

        static com.fasterxml.jackson.databind.JsonNode limit(int n) {
            return M.createObjectNode().put("limit", n);
        }

        static com.fasterxml.jackson.databind.JsonNode xy(String x, String y) {
            return M.createObjectNode().put("x", x).put("y", y);
        }

        static com.fasterxml.jackson.databind.JsonNode time(String col, String grain) {
            return M.createObjectNode().put("time_column", col).put("time_grain", grain);
        }
    }
}
