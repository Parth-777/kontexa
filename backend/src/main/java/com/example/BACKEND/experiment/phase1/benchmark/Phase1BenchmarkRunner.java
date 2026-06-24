package com.example.BACKEND.experiment.phase1.benchmark;

import com.example.BACKEND.experiment.phase1.Phase1CatalogueFactory;
import com.example.BACKEND.experiment.phase1.Phase1CataloguePayloadMode;
import com.example.BACKEND.experiment.phase1.Phase1DatasetRegistry;
import com.example.BACKEND.experiment.phase1.Phase1LlmPlannerExperiment;
import com.example.BACKEND.experiment.phase1.Phase1PlannerInput;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

/**
 * Runs Phase-1 LLM benchmark and compares against the current semantic pipeline.
 */
public final class Phase1BenchmarkRunner {

    private final Phase1LlmPlannerExperiment llmExperiment;
    private final Phase1CurrentPipelineRunner currentPipeline;
    private final ObjectMapper mapper;

    public Phase1BenchmarkRunner(
            Phase1LlmPlannerExperiment llmExperiment,
            Phase1CurrentPipelineRunner currentPipeline,
            ObjectMapper mapper
    ) {
        this.llmExperiment = llmExperiment;
        this.currentPipeline = currentPipeline;
        this.mapper = mapper != null ? mapper : new ObjectMapper();
    }

    public record ComparisonResult(
            Phase1BenchmarkReport currentPipeline,
            Phase1BenchmarkReport phase1Llm,
            List<String> failures
    ) {}

    public ComparisonResult run(List<Phase1FactualCase> cases) {
        List<Phase1CaseScore> currentScores = new ArrayList<>();
        List<Phase1CaseScore> llmScores = new ArrayList<>();
        List<String> failures = new ArrayList<>();

        for (int i = 0; i < cases.size(); i++) {
            Phase1FactualCase c = cases.get(i);
            var dataset = Phase1DatasetRegistry.get(c.datasetId());
            var bundle = dataset.bundle();

            try {
                var currentRun = currentPipeline.run(c.question(), bundle);
                currentScores.add(Phase1CaseScore.scoreCurrent(c, currentRun));
            } catch (Exception e) {
                failures.add("CURRENT case " + (i + 1) + ": " + e.getMessage());
                currentScores.add(failedScore(c, "current", e.getMessage()));
            }

            try {
                var input = new Phase1PlannerInput(
                        c.question(),
                        Phase1CatalogueFactory.catalogueFrom(
                                c.datasetId(), bundle, Phase1CataloguePayloadMode.WITH_DESCRIPTIONS),
                        Phase1CatalogueFactory.schemaFrom(bundle));
                var out = llmExperiment.plan(input, bundle);
                llmScores.add(Phase1CaseScore.scoreLlm(c, out));
                Thread.sleep(150);
            } catch (Exception e) {
                String msg = e.getClass().getSimpleName() + ": " + String.valueOf(e.getMessage());
                failures.add("LLM case " + (i + 1) + " [" + c.datasetId() + "]: " + msg);
                llmScores.add(failedScore(c, "llm", msg));
            }
        }

        return new ComparisonResult(
                Phase1BenchmarkReport.aggregate("current_pipeline", currentScores),
                Phase1BenchmarkReport.aggregate("phase1_llm", llmScores),
                failures);
    }

    public void writeReport(ComparisonResult result, Path outFile) throws IOException {
        Files.createDirectories(outFile.getParent());
        try (PrintWriter w = new PrintWriter(Files.newBufferedWriter(outFile))) {
            w.println("Phase-1 LLM Planner Benchmark — " + Instant.now());
            w.println("Questions: " + result.currentPipeline().total());
            w.println();
            w.println(result.currentPipeline().summaryLine());
            w.println(result.phase1Llm().summaryLine());
            w.println();
            w.println("DELTA (phase1_llm - current):");
            w.printf("  metric:    %+d%n", result.phase1Llm().metricCorrect() - result.currentPipeline().metricCorrect());
            w.printf("  dimension: %+d%n", result.phase1Llm().dimensionCorrect() - result.currentPipeline().dimensionCorrect());
            w.printf("  filter:    %+d%n", result.phase1Llm().filterCorrect() - result.currentPipeline().filterCorrect());
            w.printf("  sql:       %+d%n", result.phase1Llm().sqlCorrect() - result.currentPipeline().sqlCorrect());
            w.printf("  execution: %+d%n", result.phase1Llm().executionSuccess() - result.currentPipeline().executionSuccess());
            w.println();
            if (!result.failures().isEmpty()) {
                w.println("RUNTIME FAILURES:");
                result.failures().forEach(f -> w.println("  " + f));
                w.println();
            }
            w.println("MISSES — current pipeline:");
            for (Phase1CaseScore s : result.currentPipeline().cases()) {
                if (!s.metricCorrect || !s.dimensionCorrect || !s.sqlCorrect) {
                    w.printf("  [%s] %s | metric=%s dim=%s | %s%n",
                            s.testCase.datasetId(), s.testCase.question(),
                            s.resolvedMetric, s.resolvedDimension, s.notes);
                }
            }
            w.println();
            w.println("MISSES — phase1 LLM:");
            for (Phase1CaseScore s : result.phase1Llm().cases()) {
                if (!s.metricCorrect || !s.dimensionCorrect || !s.filterCorrect || !s.sqlCorrect) {
                    w.printf("  [%s] %s | metric=%s dim=%s%n",
                            s.testCase.datasetId(), s.testCase.question(),
                            s.resolvedMetric, s.resolvedDimension);
                }
            }
        }
    }

    private static Phase1CaseScore failedScore(Phase1FactualCase c, String pipe, String msg) {
        return new Phase1CaseScore(c, false, false, false, false, false,
                null, null, "", pipe + " error: " + msg);
    }

    public static String resolveApiKey() {
        String env = System.getenv("OPENAI_API_KEY");
        if (env != null && !env.isBlank()) return env.trim();
        try (InputStream is = Phase1BenchmarkRunner.class.getResourceAsStream("/application.properties")) {
            if (is != null) {
                Properties p = new Properties();
                p.load(is);
                String key = p.getProperty("openai.api.key");
                if (key != null && !key.isBlank()) return key.trim();
            }
        } catch (IOException ignored) { }
        return null;
    }

    public static String resolveModel() {
        String env = System.getenv("OPENAI_MODEL");
        if (env != null && !env.isBlank()) return env.trim();
        try (InputStream is = Phase1BenchmarkRunner.class.getResourceAsStream("/application.properties")) {
            if (is != null) {
                Properties p = new Properties();
                p.load(is);
                String model = p.getProperty("openai.model");
                if (model != null && !model.isBlank()) return model.trim();
            }
        } catch (IOException ignored) { }
        return "gpt-4o-mini";
    }
}
