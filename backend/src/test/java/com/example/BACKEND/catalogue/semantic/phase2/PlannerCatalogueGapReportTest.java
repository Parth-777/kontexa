package com.example.BACKEND.catalogue.semantic.phase2;

import com.example.BACKEND.catalogue.decision.execution.sqltemplates.SqlTemplateTestHarness;
import com.example.BACKEND.catalogue.decision.semantics.catalog.MetricResolutionTestSupport;
import com.example.BACKEND.catalogue.semantic.canonical.CanonicalQueryValidator;
import com.example.BACKEND.catalogue.semantic.canonical.SqlFidelityBenchmarkRunner;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PlannerCatalogueGapReportTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void generatePlannerCatalogueGapReport() throws Exception {
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
        var analysisAdapter = new SemanticPlanToAnalysisPlanAdapter();
        var harness = SqlTemplateTestHarness.create();
        var validator = new CanonicalQueryValidator(new SemanticPlanningProperties());

        List<SqlFidelityBenchmarkRunner.BenchmarkCase> cases =
                SqlFidelityBenchmarkRunner.loadCases(
                        gap, shadowFirst20, fidelityLog, shadowLog, 50,
                        catalogue, analysisAdapter, harness.planner, bundle, MAPPER);
        assertFalse(cases.isEmpty());

        PlannerCatalogueGapAnalyzer analyzer = new PlannerCatalogueGapAnalyzer();
        PlannerCatalogueGapAnalyzer.Summary summary = analyzer.analyze(cases, catalogue, validator);

        Path jsonReport = Path.of("target/planner-catalogue-gap-report.json");
        Path mdReport = Path.of("target/planner-catalogue-gap-summary.md");
        PlannerCatalogueGapAnalyzer.writeJsonReport(summary, jsonReport, MAPPER);
        PlannerCatalogueGapAnalyzer.writeMarkdownSummary(summary, mdReport);

        System.out.printf("""
                Planner-catalogue gap audit (n=%d)
                Validation failures: %d/%d
                Unique field gaps: %d
                JSON: %s
                Markdown: %s
                """,
                summary.totalCases(),
                summary.validationFailureCases(), summary.totalCases(),
                summary.uniqueFieldGaps().size(),
                jsonReport.toAbsolutePath(),
                mdReport.toAbsolutePath());

        summary.categoryFrequency().entrySet().stream()
                .filter(e -> e.getValue() > 0)
                .sorted((a, b) -> Integer.compare(b.getValue(), a.getValue()))
                .forEach(e -> System.out.println("  category " + e.getKey() + ": " + e.getValue()));

        summary.plannerFieldFrequency().entrySet().stream()
                .sorted((a, b) -> Integer.compare(b.getValue(), a.getValue()))
                .limit(10)
                .forEach(e -> System.out.println("  field " + e.getKey() + ": " + e.getValue()));

        assertTrue(Files.exists(jsonReport));
        assertTrue(Files.exists(mdReport));
        assertEquals(summary.totalCases(),
                summary.validationFailureCases() + summary.validationPassCases());
        assertFalse(summary.failures().isEmpty(), "expected validation failures in benchmark");
    }
}
