package com.example.BACKEND.catalogue.semantic.canonical;

import com.example.BACKEND.catalogue.decision.execution.sqltemplates.SqlTemplateTestHarness;
import com.example.BACKEND.catalogue.decision.semantics.catalog.MetricResolutionTestSupport;
import com.example.BACKEND.catalogue.semantic.phase2.SemanticCatalogueFactory;
import com.example.BACKEND.catalogue.semantic.phase2.SemanticPlanToAnalysisPlanAdapter;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SqlFidelityReportTest {

    private final CanonicalQueryModelAdapter adapter = new CanonicalQueryModelAdapter();

    @Test
    void detectsAggregationMutation() {
        var plan = new com.example.BACKEND.catalogue.semantic.phase2.StructuredSemanticPlan(
                "COMPARISON", "profit_margin", null,
                List.of("region"), List.of(),
                new com.example.BACKEND.catalogue.semantic.phase2.StructuredSemanticPlan.SemanticAggregations("AVG", null),
                null, null, null, null,
                0.9, "avg", List.of());

        CanonicalQueryModel canonical = adapter.adapt(plan);
        String sql = "SELECT region, SUM(profit_margin) AS metric_value FROM oil GROUP BY region";

        SqlFidelityReport.Result report = SqlFidelityReport.compare(canonical, sql);

        assertFalse(report.aggregationMatch());
        assertEquals("aggregation", report.mutations().get(0).field());
        assertEquals("AVG", report.mutations().get(0).expected());
        assertEquals("SUM", report.mutations().get(0).actual());
    }

    @Test
    void detectsSelfCorrelation() {
        var plan = new com.example.BACKEND.catalogue.semantic.phase2.StructuredSemanticPlan(
                "RELATIONSHIP", "carbon_emission_tons", "profit_margin",
                List.of(), List.of(),
                new com.example.BACKEND.catalogue.semantic.phase2.StructuredSemanticPlan.SemanticAggregations(null, null),
                null, null, "carbon_emission_tons", null,
                0.9, "corr", List.of());

        CanonicalQueryModel canonical = adapter.adapt(plan);
        String sql = """
                SELECT CORR(carbon_emission_tons, carbon_emission_tons) AS correlation_coefficient
                FROM oil""";

        SqlFidelityReport.Result report = SqlFidelityReport.compare(canonical, sql);

        assertFalse(report.relationshipMatch());
        assertTrue(report.mutations().stream()
                .anyMatch(m -> "relationshipOperands".equals(m.field())));
    }

    @Test
    void detectsLimitMutation() {
        var plan = new com.example.BACKEND.catalogue.semantic.phase2.StructuredSemanticPlan(
                "RANKING", "total_revenue", null,
                List.of("region"), List.of(),
                new com.example.BACKEND.catalogue.semantic.phase2.StructuredSemanticPlan.SemanticAggregations("SUM", null),
                new com.example.BACKEND.catalogue.semantic.phase2.StructuredSemanticPlan.SemanticOrdering("total_revenue", "DESC"),
                1, null, null,
                0.9, "top one", List.of());

        CanonicalQueryModel canonical = adapter.adapt(plan);
        String sql = """
                SELECT region, SUM(total_revenue) AS metric_value
                FROM oil GROUP BY region ORDER BY metric_value DESC LIMIT 10""";

        SqlFidelityReport.Result report = SqlFidelityReport.compare(canonical, sql);

        assertFalse(report.limitMatch());
        assertEquals("1", report.mutations().stream()
                .filter(m -> "limit".equals(m.field()))
                .findFirst()
                .orElseThrow()
                .expected());
        assertEquals("10", report.mutations().stream()
                .filter(m -> "limit".equals(m.field()))
                .findFirst()
                .orElseThrow()
                .actual());
    }
}

class SqlFidelityBenchmarkTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void runBenchmarkAndWriteReport() throws Exception {
        Path gap = Path.of("target/phase2-gap-analysis.json");
        Path shadowFirst20 = Path.of("target/phase2-shadow-first20.json");
        Path fidelityLog = Path.of("target/semantic-fidelity.log");
        Path shadowLog = Path.of("target/phase2-shadow.log");

        if (!Files.exists(gap) && !Files.exists(shadowFirst20)
                && !Files.exists(fidelityLog) && !Files.exists(shadowLog)) {
            System.out.println("SKIP: no SQL fidelity benchmark artifacts found under target/");
            return;
        }

        var bundle = MetricResolutionTestSupport.oilBundle();
        var catalogue = SemanticCatalogueFactory.catalogueFrom(null, bundle);
        var adapter = new SemanticPlanToAnalysisPlanAdapter();
        var sqlPlanner = SqlTemplateTestHarness.create().planner;

        List<SqlFidelityBenchmarkRunner.BenchmarkCase> cases =
                SqlFidelityBenchmarkRunner.loadCases(
                        gap, shadowFirst20, fidelityLog, shadowLog, 50,
                        catalogue, adapter, sqlPlanner, bundle, MAPPER);
        assertFalse(cases.isEmpty());

        SqlFidelityBenchmarkRunner.BenchmarkSummary summary =
                SqlFidelityBenchmarkRunner.run(cases, MAPPER);

        Path reportPath = Path.of("target/sql-fidelity-report.json");
        SqlFidelityBenchmarkRunner.writeReport(summary, reportPath, MAPPER);

        System.out.printf("""
                SQL Fidelity Summary (n=%d)
                Total SQL fidelity: %d / %d (%.0f%%)
                measureMatch: %d/%d
                aggregationMatch: %d/%d
                partitionMatch: %d/%d
                orderingMatch: %d/%d
                limitMatch: %d/%d
                relationshipMatch: %d/%d
                timeGrainMatch: %d/%d
                report: %s
                """,
                summary.totalCases(),
                summary.sqlFidelityPassCount(), summary.totalCases(),
                100.0 * summary.sqlFidelityPassCount() / summary.totalCases(),
                summary.measureMatch(), summary.totalCases(),
                summary.aggregationMatch(), summary.totalCases(),
                summary.partitionMatch(), summary.totalCases(),
                summary.orderingMatch(), summary.totalCases(),
                summary.limitMatch(), summary.totalCases(),
                summary.relationshipMatch(), summary.totalCases(),
                summary.timeGrainMatch(), summary.totalCases(),
                reportPath.toAbsolutePath());

        summary.failureAttribution().forEach((field, suspects) -> {
            System.out.println(field + " failures:");
            suspects.forEach((suspect, count) -> System.out.println("  " + suspect + " -> " + count));
        });

        assertTrue(Files.exists(reportPath));
        assertTrue(summary.totalCases() > 0);
    }
}
