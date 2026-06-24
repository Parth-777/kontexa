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
import com.example.BACKEND.experiment.phase1.benchmark.Phase1FactualQuestionBank;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

/**
 * Phase-1 benchmark: 50 factual questions × (current pipeline vs GPT planner).
 *
 * Requires OpenAI API key (env OPENAI_API_KEY or application.properties).
 * Report: target/phase1-llm-benchmark.log
 */
class Phase1BenchmarkRunnerTest {

    @Test
    void runFiftyQuestionBenchmark() throws Exception {
        String apiKey = Phase1BenchmarkRunner.resolveApiKey();
        Assumptions.assumeTrue(apiKey != null && !apiKey.isBlank(),
                "OPENAI_API_KEY required for Phase-1 LLM benchmark");

        var harness = SqlTemplateTestHarness.create();
        DeterministicAnalyticalQueryPlanner sqlPlanner = harness.planner;

        QuestionSemanticExtractor extractor = MetricResolutionTestSupport.extractor();
        MetricResolutionEngine metricEngine = MetricResolutionTestSupport.engine();
        QuestionInvestigationPlanner investigationPlanner = UniversalPlannerTestSupport.investigationPlanner();
        UniversalAnalysisPlanner analysisPlanner = UniversalPlannerTestSupport.universalPlanner();

        Phase1CurrentPipelineRunner current = new Phase1CurrentPipelineRunner(
                extractor, metricEngine, investigationPlanner, analysisPlanner, sqlPlanner);

        Phase1OpenAiStructuredClient client = new Phase1OpenAiStructuredClient(
                apiKey, Phase1BenchmarkRunner.resolveModel(), new ObjectMapper());

        Phase1LlmPlannerExperiment experiment = new Phase1LlmPlannerExperiment(
                client, sqlPlanner, new ObjectMapper());

        Phase1BenchmarkRunner runner = new Phase1BenchmarkRunner(experiment, current, new ObjectMapper());
        var cases = Phase1FactualQuestionBank.all();

        var result = runner.run(cases);
        Path report = Path.of("target", "phase1-llm-benchmark.log");
        runner.writeReport(result, report);

        System.out.println("=== PHASE-1 BENCHMARK ===");
        System.out.println(result.currentPipeline().summaryLine());
        System.out.println(result.phase1Llm().summaryLine());
        System.out.println("Report: " + report.toAbsolutePath());
        if (!result.failures().isEmpty()) {
            System.out.println("Failures: " + result.failures().size());
            result.failures().forEach(System.out::println);
        }
    }
}
