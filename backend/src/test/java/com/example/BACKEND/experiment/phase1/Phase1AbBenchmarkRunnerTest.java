package com.example.BACKEND.experiment.phase1;

import com.example.BACKEND.catalogue.decision.execution.sqltemplates.DeterministicAnalyticalQueryPlanner;
import com.example.BACKEND.catalogue.decision.execution.sqltemplates.SqlTemplateTestHarness;
import com.example.BACKEND.experiment.phase1.benchmark.Phase1AbBenchmarkRunner;
import com.example.BACKEND.experiment.phase1.benchmark.Phase1BenchmarkRunner;
import com.example.BACKEND.experiment.phase1.benchmark.Phase1FactualCase;
import com.example.BACKEND.experiment.phase1.benchmark.Phase1NaturalQuestionGenerator;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

/**
 * A/B benchmark: 500+ factual questions × (schema-only vs catalogue descriptions).
 *
 * Set PHASE1_BENCHMARK_MAX to cap questions (default: all generated, typically 600).
 * Report: target/phase1-ab-benchmark.log
 */
class Phase1AbBenchmarkRunnerTest {

    @Test
    void generateAtLeastFiveHundredQuestions() {
        List<Phase1FactualCase> all = Phase1NaturalQuestionGenerator.generateAll();
        System.out.println("Generated questions: " + all.size());
        System.out.println("Datasets: " + all.stream().map(Phase1FactualCase::datasetId).distinct().count());
        org.junit.jupiter.api.Assertions.assertTrue(all.size() >= 500,
                "Expected at least 500 questions, got " + all.size());
    }

    @Test
    void runAbBenchmark() throws Exception {
        String apiKey = Phase1BenchmarkRunner.resolveApiKey();
        Assumptions.assumeTrue(apiKey != null && !apiKey.isBlank(),
                "OPENAI_API_KEY required for Phase-1 A/B benchmark");

        List<Phase1FactualCase> all = Phase1NaturalQuestionGenerator.generateAll();
        int limit = Phase1AbBenchmarkRunner.resolveBenchmarkLimit(all.size());
        List<Phase1FactualCase> cases = all.subList(0, Math.min(limit, all.size()));

        DeterministicAnalyticalQueryPlanner sqlPlanner = SqlTemplateTestHarness.create().planner;
        Phase1OpenAiStructuredClient client = new Phase1OpenAiStructuredClient(
                apiKey, Phase1BenchmarkRunner.resolveModel(), new ObjectMapper());
        Phase1LlmPlannerExperiment experiment = new Phase1LlmPlannerExperiment(
                client, sqlPlanner, new ObjectMapper());

        Phase1AbBenchmarkRunner runner = new Phase1AbBenchmarkRunner(experiment);
        System.out.println("Running A/B benchmark on " + cases.size() + " questions...");
        var result = runner.run(cases, 100);

        Path report = Path.of("target", "phase1-ab-benchmark.log");
        runner.writeReport(result, report);

        System.out.println("=== PHASE-1 A/B BENCHMARK ===");
        System.out.println("Questions: " + cases.size());
        System.out.println(result.schemaOnly().summaryLine());
        System.out.println(result.withDescriptions().summaryLine());
        System.out.printf("DELTA dimension: %+d%n",
                result.withDescriptions().dimensionCorrect() - result.schemaOnly().dimensionCorrect());
        System.out.println("Report: " + report.toAbsolutePath());
    }
}
