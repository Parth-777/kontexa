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

class AdapterFieldMutationReportTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void generateAdapterFieldMutationReport() throws Exception {
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
        SqlTemplateTestHarness.create();

        List<SqlFidelityBenchmarkRunner.BenchmarkCase> cases =
                SqlFidelityBenchmarkRunner.loadCases(
                        gap, shadowFirst20, fidelityLog, shadowLog, 50,
                        catalogue, adapter, SqlTemplateTestHarness.create().planner, bundle, MAPPER);
        assertFalse(cases.isEmpty());

        AdapterFieldMutationAnalyzer analyzer = new AdapterFieldMutationAnalyzer(adapter);
        AdapterFieldMutationAnalyzer.Summary summary = analyzer.analyze(cases, catalogue);

        Path jsonReport = Path.of("target/adapter-field-mutation-report.json");
        Path mdReport = Path.of("target/adapter-field-mutation-summary.md");
        AdapterFieldMutationAnalyzer.writeJsonReport(summary, jsonReport, MAPPER);
        AdapterFieldMutationAnalyzer.writeMarkdownSummary(summary, mdReport);

        System.out.printf("""
                Adapter field mutation audit (n=%d)
                JSON: %s
                Markdown: %s
                """,
                summary.totalCases(),
                jsonReport.toAbsolutePath(),
                mdReport.toAbsolutePath());

        summary.fieldStatusCounts().forEach((field, statuses) -> {
            System.out.print(field + ": ");
            statuses.forEach((status, sc) -> System.out.print(status + "=" + sc.count() + " "));
            System.out.println();
        });

        assertTrue(Files.exists(jsonReport));
        assertTrue(Files.exists(mdReport));
        assertTrue(summary.totalCases() > 0);
    }
}
