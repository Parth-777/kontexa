package com.example.BACKEND.catalogue.semantic.canonical;

import com.example.BACKEND.catalogue.decision.execution.sqltemplates.SqlTemplateTestHarness;
import com.example.BACKEND.catalogue.decision.semantics.catalog.MetricResolutionTestSupport;
import com.example.BACKEND.catalogue.semantic.phase2.SemanticCatalogueFactory;
import com.example.BACKEND.catalogue.semantic.phase2.SemanticPlanToAnalysisPlanAdapter;
import com.example.BACKEND.catalogue.semantic.phase2.SemanticPlanningProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class CanonicalSqlDiffBenchmarkTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void generateCanonicalSqlDiffReport() throws Exception {
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
        var canonicalAdapter = new CanonicalQueryModelAdapter();
        var canonicalValidator = new CanonicalQueryValidator(new SemanticPlanningProperties());
        var canonicalRenderer = new CanonicalSqlRenderer();

        List<SqlFidelityBenchmarkRunner.BenchmarkCase> cases =
                SqlFidelityBenchmarkRunner.loadCases(
                        gap, shadowFirst20, fidelityLog, shadowLog, 50,
                        catalogue, analysisAdapter, harness.planner, bundle, MAPPER);
        assertFalse(cases.isEmpty());

        CanonicalSqlDiffBenchmarkRunner.DiffSummary summary =
                CanonicalSqlDiffBenchmarkRunner.run(
                        cases, catalogue, analysisAdapter, harness.planner, bundle,
                        canonicalAdapter, canonicalValidator, canonicalRenderer, MAPPER);

        Path jsonReport = Path.of("target/canonical-sql-diff-report.json");
        Path mdReport = Path.of("target/canonical-sql-diff-summary.md");
        CanonicalSqlDiffBenchmarkRunner.writeJsonReport(summary, jsonReport, MAPPER);
        CanonicalSqlDiffBenchmarkRunner.writeMarkdownSummary(summary, mdReport);

        System.out.printf("""
                Canonical SQL diff benchmark (n=%d)
                Validation pass: %d/%d
                Canonical self-fidelity pass: %d/%d
                Legacy vs canonical fidelity pass: %d/%d
                SQL text match: %d/%d
                JSON: %s
                Markdown: %s
                """,
                summary.totalCases(),
                summary.canonicalValidationPass(), summary.totalCases(),
                summary.canonicalSelfFidelityPass(), summary.totalCases(),
                summary.legacyVsCanonicalFidelityPass(), summary.totalCases(),
                summary.sqlTextMatchCount(), summary.totalCases(),
                jsonReport.toAbsolutePath(),
                mdReport.toAbsolutePath());

        summary.legacyVsCanonicalMutationFields().forEach((field, count) ->
                System.out.println("  legacy drift field " + field + ": " + count));

        assertTrue(Files.exists(jsonReport));
        assertTrue(Files.exists(mdReport));
        assertEquals(summary.canonicalRenderable(), summary.canonicalSelfFidelityPass(),
                "every rendered canonical SQL must be faithful to CanonicalQueryModel");
    }
}
