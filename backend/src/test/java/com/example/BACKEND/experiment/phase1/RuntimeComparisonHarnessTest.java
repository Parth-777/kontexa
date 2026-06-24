package com.example.BACKEND.experiment.phase1;

import com.example.BACKEND.catalogue.decision.execution.sqltemplates.DeterministicAnalyticalQueryPlanner;
import com.example.BACKEND.catalogue.decision.execution.sqltemplates.SqlTemplateTestHarness;
import com.example.BACKEND.catalogue.decision.investigation.QuestionInvestigationPlanner;
import com.example.BACKEND.catalogue.decision.planning.UniversalAnalysisPlanner;
import com.example.BACKEND.catalogue.decision.semantics.MetricResolutionEngine;
import com.example.BACKEND.catalogue.decision.semantics.QuestionSemanticExtractor;
import com.example.BACKEND.catalogue.decision.semantics.catalog.MetricResolutionTestSupport;
import com.example.BACKEND.catalogue.decision.semantics.catalog.UniversalPlannerTestSupport;
import com.example.BACKEND.experiment.phase1.benchmark.Phase1BenchmarkRunner;
import com.example.BACKEND.experiment.phase1.benchmark.Phase1CurrentPipelineRunner;
import com.example.BACKEND.experiment.phase1.comparison.RuntimeComparisonHarness;
import com.example.BACKEND.experiment.phase1.comparison.RuntimeComparisonQuestionBank;
import com.example.BACKEND.experiment.phase1.comparison.RuntimeComparisonReportWriter;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

/**
 * Side-by-side runtime comparison: production pipeline vs GPT+catalogue planner.
 *
 * 100 human-authored questions never used in any prior benchmark.
 *
 * Set RUNTIME_COMPARISON_MAX to cap questions (default: all 100).
 * Report: target/runtime-planner-comparison.log
 *
 * Requires OPENAI_API_KEY for GPT arm.
 */
class RuntimeComparisonHarnessTest {

    @Test
    void questionBankHasOneHundredHumanQuestions() {
        var all = RuntimeComparisonQuestionBank.all();
        System.out.println("Human comparison questions: " + all.size());
        System.out.println("Datasets: " + all.stream().map(q -> q.datasetId()).distinct().count());
        Assertions.assertEquals(100, all.size());
        Assertions.assertTrue(all.stream().map(q -> q.datasetId()).distinct().count() >= 10);
    }

    @Test
    void runRuntimeComparison() throws Exception {
        String apiKey = Phase1BenchmarkRunner.resolveApiKey();
        Assumptions.assumeTrue(apiKey != null && !apiKey.isBlank(),
                "OPENAI_API_KEY required for GPT arm of runtime comparison");

        var harness = SqlTemplateTestHarness.create();
        Phase1CurrentPipelineRunner production = buildProductionRunner(harness.planner);
        Phase1LlmPlannerExperiment gpt = new Phase1LlmPlannerExperiment(
                new Phase1OpenAiStructuredClient(
                        apiKey, Phase1BenchmarkRunner.resolveModel(), new ObjectMapper()),
                harness.planner,
                new ObjectMapper());

        List<com.example.BACKEND.experiment.phase1.comparison.RuntimeComparisonQuestion> all =
                RuntimeComparisonQuestionBank.all();
        int limit = RuntimeComparisonHarness.resolveQuestionLimit(all.size());
        var questions = all.subList(0, Math.min(limit, all.size()));

        RuntimeComparisonHarness runner = new RuntimeComparisonHarness(production, gpt);
        System.out.println("Running runtime comparison on " + questions.size() + " human questions...");
        var result = runner.run(questions, 120);

        Path report = Path.of("target", "runtime-planner-comparison.log");
        RuntimeComparisonReportWriter.write(result, report);

        int prodOk = (int) result.cases().stream()
                .filter(RuntimeComparisonHarness.SideBySideCase::productionSucceeded).count();
        int gptOk = (int) result.cases().stream()
                .filter(RuntimeComparisonHarness.SideBySideCase::gptSucceeded).count();

        System.out.println("=== RUNTIME PLANNER COMPARISON ===");
        System.out.println("Questions: " + questions.size());
        System.out.printf("Production execution: %d/%d%n", prodOk, questions.size());
        System.out.printf("GPT+catalogue execution: %d/%d%n", gptOk, questions.size());
        System.out.printf("DELTA (GPT - production): %+d%n", gptOk - prodOk);
        System.out.println("Report: " + report.toAbsolutePath());
    }

    private static Phase1CurrentPipelineRunner buildProductionRunner(
            DeterministicAnalyticalQueryPlanner sqlPlanner
    ) {
        QuestionSemanticExtractor extractor = MetricResolutionTestSupport.extractor();
        MetricResolutionEngine metricEngine = MetricResolutionTestSupport.engine();
        QuestionInvestigationPlanner investigationPlanner =
                UniversalPlannerTestSupport.investigationPlanner();
        UniversalAnalysisPlanner analysisPlanner = UniversalPlannerTestSupport.universalPlanner();
        return new Phase1CurrentPipelineRunner(
                extractor, metricEngine, investigationPlanner, analysisPlanner, sqlPlanner);
    }
}
