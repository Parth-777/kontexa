package com.example.BACKEND.catalogue.semantic.phase2;

import com.example.BACKEND.catalogue.decision.execution.sqltemplates.SqlTemplateTestHarness;
import com.example.BACKEND.catalogue.decision.semantics.catalog.MetricResolutionTestSupport;
import com.example.BACKEND.catalogue.semantic.canonical.SqlFidelityBenchmarkRunner;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Generates definitive root-cause evidence for SQL semantic drift.
 */
class SqlDriftRootCauseReportTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void generateRootCauseReport() throws Exception {
        Path gap = Path.of("target/phase2-gap-analysis.json");
        Path shadowFirst20 = Path.of("target/phase2-shadow-first20.json");
        Path fidelityLog = Path.of("target/semantic-fidelity.log");
        Path shadowLog = Path.of("target/phase2-shadow.log");

        if (!Files.exists(gap) && !Files.exists(shadowFirst20)
                && !Files.exists(fidelityLog) && !Files.exists(shadowLog)) {
            System.out.println("SKIP: no benchmark artifacts found under target/");
            return;
        }

        var bundle = MetricResolutionTestSupport.oilBundle();
        var catalogue = SemanticCatalogueFactory.catalogueFrom(null, bundle);
        var adapter = new SemanticPlanToAnalysisPlanAdapter();
        var harness = SqlTemplateTestHarness.create();

        List<SqlFidelityBenchmarkRunner.BenchmarkCase> cases =
                SqlFidelityBenchmarkRunner.loadCases(
                        gap, shadowFirst20, fidelityLog, shadowLog, 50,
                        catalogue, adapter, harness.planner, bundle, MAPPER);
        assertFalse(cases.isEmpty());

        SqlDriftRootCauseAnalyzer analyzer = new SqlDriftRootCauseAnalyzer(
                adapter,
                harness.planner,
                new com.example.BACKEND.catalogue.decision.execution.sqltemplates.IntentAggregationStrategy(),
                new com.example.BACKEND.catalogue.decision.execution.sqltemplates.GroupedMetricSqlBuilder(
                        new com.example.BACKEND.catalogue.decision.execution.sqltemplates.IntentAggregationStrategy()),
                new com.example.BACKEND.catalogue.decision.execution.sqltemplates.RankingSqlTemplate(
                        new com.example.BACKEND.catalogue.decision.execution.sqltemplates.GroupedMetricSqlBuilder(
                                new com.example.BACKEND.catalogue.decision.execution.sqltemplates.IntentAggregationStrategy())));

        SqlDriftRootCauseAnalyzer.RootCauseSummary summary = analyzer.analyze(cases, catalogue, bundle);

        Path jsonReport = Path.of("target/sql-drift-root-cause-report.json");
        Path mdReport = Path.of("target/sql-drift-summary.md");
        SqlDriftRootCauseAnalyzer.writeJsonReport(summary, jsonReport, MAPPER);
        SqlDriftRootCauseAnalyzer.writeMarkdownSummary(summary, mdReport);

        System.out.printf("""
                SQL drift root-cause analysis (n=%d)
                SQL fidelity pass: %d / %d
                Failed cases traced: %d
                JSON: %s
                Markdown: %s
                """,
                summary.totalCases(),
                summary.sqlFidelityPassCount(),
                summary.totalCases(),
                summary.failedCases(),
                jsonReport.toAbsolutePath(),
                mdReport.toAbsolutePath());

        summary.groupedMutations().forEach((field, entries) -> {
            System.out.println(field + " mutations:");
            for (SqlDriftRootCauseAnalyzer.GroupedMutation gm : entries) {
                System.out.printf("  %s.%s:%d -> %d%n",
                        gm.className(), gm.methodName(), gm.lineNumber(), gm.count());
            }
        });

        assertTrue(Files.exists(jsonReport));
        assertTrue(Files.exists(mdReport));
        assertTrue(summary.totalCases() > 0);
    }
}
