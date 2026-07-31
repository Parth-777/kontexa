package com.example.BACKEND.catalogue.investigation;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

class EvidenceRequestSqlCompilerNestedTest {

    private final EvidenceRequestSqlCompiler compiler =
            new EvidenceRequestSqlCompiler(new SlicePredicateBuilder());

    @Test
    void emitsNestedDimensionInGroupBy() {
        InvestigationPlan plan = new InvestigationPlan(
                new InvestigationPlan.Phenomenon(
                        "traffic sources",
                        new InvestigationObjective.Measure("visitId", "COUNT", List.of())),
                List.of(),
                List.of(new InvestigationPlan.EvidenceRequest(
                        "1", "metric_by_dimension", null,
                        new InvestigationObjective.Measure("visitId", "COUNT", List.of()),
                        "trafficSource.source",
                        null, null, null, null)),
                0.9);

        var compiled = compiler.compile(plan, "ga_sessions_20160801");
        assertTrue(compiled.requests().getFirst().ok());
        String sql = compiled.requests().getFirst().spec().sql();
        assertTrue(sql.contains("SELECT trafficSource.source AS member"));
        assertTrue(sql.contains("GROUP BY trafficSource.source"));
    }

    @Test
    void nycTaxiFlatSqlUnchanged() {
        InvestigationPlan plan = new InvestigationPlan(
                new InvestigationPlan.Phenomenon(
                        "regions",
                        new InvestigationObjective.Measure("tip_amount", "AVG", List.of())),
                List.of(),
                List.of(new InvestigationPlan.EvidenceRequest(
                        "1", "metric_by_dimension", null,
                        new InvestigationObjective.Measure("tip_amount", "AVG", List.of()),
                        "region",
                        null, null, null, null)),
                0.9);

        var compiled = compiler.compile(plan, "yellow_taxi_trips");
        String sql = compiled.requests().getFirst().spec().sql();
        assertTrue(sql.contains("SELECT region AS member"));
        assertTrue(sql.contains("GROUP BY region"));
    }
}
