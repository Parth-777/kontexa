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

/**
 * Replays stored benchmark GPT plans and writes a field-preservation report.
 */
class SemanticFidelityBenchmarkTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void replayBenchmarkPlansAndWriteReport() throws Exception {
        Path gap = Path.of("target/phase2-gap-analysis.json");
        Path shadow = Path.of("target/phase2-shadow.log");
        if (!Files.exists(gap) && !Files.exists(shadow)) {
            System.out.println("SKIP: no benchmark artifacts at target/phase2-gap-analysis.json or target/phase2-shadow.log");
            return;
        }

        List<SemanticFidelityBenchmarkRunner.BenchmarkCase> cases =
                SemanticFidelityBenchmarkRunner.loadLastCases(gap, shadow, 50);
        assertFalse(cases.isEmpty(), "expected benchmark cases from gap analysis or shadow log");

        var bundle = MetricResolutionTestSupport.oilBundle();
        var catalogue = SemanticCatalogueFactory.catalogueFrom(null, bundle);
        var adapter = new SemanticPlanToAnalysisPlanAdapter();
        var sqlPlanner = SqlTemplateTestHarness.create().planner;

        SemanticFidelityBenchmarkRunner.BenchmarkSummary summary =
                SemanticFidelityBenchmarkRunner.run(cases, catalogue, adapter, sqlPlanner, bundle, MAPPER);

        Path reportPath = Path.of("target/semantic-fidelity-benchmark.json");
        SemanticFidelityBenchmarkRunner.writeReport(summary, reportPath, MAPPER);

        System.out.printf("""
                Semantic fidelity benchmark (n=%d, max=50)
                measurePreserved: %d/%d
                aggregationPreserved: %d/%d
                partitionPreserved: %d/%d
                orderingPreserved: %d/%d
                limitPreserved: %d/%d
                relationshipOperandsPreserved: %d/%d
                timeGrainPreserved: %d/%d
                allPreserved: %d/%d
                report: %s
                """,
                summary.totalCases(),
                summary.measurePreserved(), summary.totalCases(),
                summary.aggregationPreserved(), summary.totalCases(),
                summary.partitionPreserved(), summary.totalCases(),
                summary.orderingPreserved(), summary.totalCases(),
                summary.limitPreserved(), summary.totalCases(),
                summary.relationshipOperandsPreserved(), summary.totalCases(),
                summary.timeGrainPreserved(), summary.totalCases(),
                summary.allPreserved(), summary.totalCases(),
                reportPath.toAbsolutePath());

        assertTrue(Files.exists(reportPath));
        assertTrue(summary.totalCases() > 0);
    }
}
