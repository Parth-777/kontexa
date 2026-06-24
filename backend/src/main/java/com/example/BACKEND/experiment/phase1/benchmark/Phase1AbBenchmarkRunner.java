package com.example.BACKEND.experiment.phase1.benchmark;

import com.example.BACKEND.experiment.phase1.Phase1CatalogueFactory;
import com.example.BACKEND.experiment.phase1.Phase1CataloguePayloadMode;
import com.example.BACKEND.experiment.phase1.Phase1DatasetRegistry;
import com.example.BACKEND.experiment.phase1.Phase1LlmPlannerExperiment;
import com.example.BACKEND.experiment.phase1.Phase1PlannerInput;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * A/B benchmark: GPT + schema only vs GPT + catalogue descriptions.
 */
public final class Phase1AbBenchmarkRunner {

    private final Phase1LlmPlannerExperiment experiment;

    public Phase1AbBenchmarkRunner(Phase1LlmPlannerExperiment experiment) {
        this.experiment = experiment;
    }

    public record ArmResult(
            String armName,
            Phase1CataloguePayloadMode mode,
            int total,
            int metricCorrect,
            int dimensionCorrect,
            int filterCorrect,
            int executionSuccess,
            Map<Phase1FailureClass, Integer> failureCounts,
            List<Phase1DetailedCaseScore> cases,
            List<String> runtimeFailures
    ) {
        public String summaryLine() {
            return String.format(
                    "%s: metric=%d/%d dimension=%d/%d filter=%d/%d exec=%d/%d",
                    armName, metricCorrect, total, dimensionCorrect, total,
                    filterCorrect, total, executionSuccess, total);
        }
    }

    public record AbComparison(ArmResult schemaOnly, ArmResult withDescriptions) {}

    public AbComparison run(List<Phase1FactualCase> cases, long sleepMs) {
        List<Phase1DetailedCaseScore> scoresA = new ArrayList<>();
        List<Phase1DetailedCaseScore> scoresB = new ArrayList<>();
        List<String> failuresA = new ArrayList<>();
        List<String> failuresB = new ArrayList<>();
        Map<Phase1FailureClass, Integer> countsA = new EnumMap<>(Phase1FailureClass.class);
        Map<Phase1FailureClass, Integer> countsB = new EnumMap<>(Phase1FailureClass.class);

        for (int i = 0; i < cases.size(); i++) {
            Phase1FactualCase c = cases.get(i);
            scoresA.add(runOne(c, Phase1CataloguePayloadMode.SCHEMA_ONLY, i + 1, failuresA, countsA));
            if (sleepMs > 0) sleep(sleepMs);
            scoresB.add(runOne(c, Phase1CataloguePayloadMode.WITH_DESCRIPTIONS, i + 1, failuresB, countsB));
            if (sleepMs > 0) sleep(sleepMs);
            if ((i + 1) % 25 == 0) {
                System.out.printf("[A/B interleaved] progress %d/%d%n", i + 1, cases.size());
            }
        }

        return new AbComparison(
                aggregate("A_schema_only", Phase1CataloguePayloadMode.SCHEMA_ONLY,
                        scoresA, countsA, failuresA),
                aggregate("B_with_descriptions", Phase1CataloguePayloadMode.WITH_DESCRIPTIONS,
                        scoresB, countsB, failuresB));
    }

    private Phase1DetailedCaseScore runOne(
            Phase1FactualCase c, Phase1CataloguePayloadMode mode, int index,
            List<String> runtimeFailures, Map<Phase1FailureClass, Integer> failureCounts
    ) {
        try {
            var dataset = Phase1DatasetRegistry.get(c.datasetId());
            var bundle = dataset.bundle();
            var input = new Phase1PlannerInput(
                    c.question(),
                    Phase1CatalogueFactory.catalogueFrom(c.datasetId(), bundle, mode),
                    Phase1CatalogueFactory.schemaFrom(bundle));
            var out = experiment.plan(input, bundle);
            Phase1DetailedCaseScore score = Phase1DetailedCaseScore.score(c, out);
            for (var f : score.failures) {
                if (f.failureClass() != Phase1FailureClass.NONE) {
                    failureCounts.merge(f.failureClass(), 1, Integer::sum);
                }
            }
            return score;
        } catch (Exception e) {
            String msg = e.getClass().getSimpleName() + ": " + String.valueOf(e.getMessage());
            runtimeFailures.add("case " + index + " [" + c.datasetId() + "] " + mode + ": " + msg);
            failureCounts.merge(Phase1FailureClass.EXECUTION_FAILED, 1, Integer::sum);
            return failedScore(c, msg);
        }
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static ArmResult aggregate(
            String name, Phase1CataloguePayloadMode mode,
            List<Phase1DetailedCaseScore> scores,
            Map<Phase1FailureClass, Integer> failureCounts,
            List<String> runtimeFailures
    ) {
        return new ArmResult(
                name, mode, scores.size(),
                (int) scores.stream().filter(s -> s.metricCorrect).count(),
                (int) scores.stream().filter(s -> s.dimensionCorrect).count(),
                (int) scores.stream().filter(s -> s.filterCorrect).count(),
                (int) scores.stream().filter(s -> s.executionSuccess).count(),
                failureCounts, scores, runtimeFailures);
    }

    public void writeReport(AbComparison result, Path outFile) throws IOException {
        Files.createDirectories(outFile.getParent());
        try (PrintWriter w = new PrintWriter(Files.newBufferedWriter(outFile))) {
            w.println("Phase-1 A/B Benchmark — " + Instant.now());
            w.println("A = GPT + schema only (column names)");
            w.println("B = GPT + approved catalogue descriptions");
            w.println();
            w.println(result.schemaOnly().summaryLine());
            w.println(result.withDescriptions().summaryLine());
            w.println();
            w.println("DELTA (B - A):");
            w.printf("  metric:    %+d%n",
                    result.withDescriptions().metricCorrect() - result.schemaOnly().metricCorrect());
            w.printf("  dimension: %+d%n",
                    result.withDescriptions().dimensionCorrect() - result.schemaOnly().dimensionCorrect());
            w.printf("  filter:    %+d%n",
                    result.withDescriptions().filterCorrect() - result.schemaOnly().filterCorrect());
            w.printf("  execution: %+d%n",
                    result.withDescriptions().executionSuccess() - result.schemaOnly().executionSuccess());
            w.println();
            writeFailureBreakdown(w, "A failure breakdown", result.schemaOnly());
            w.println();
            writeFailureBreakdown(w, "B failure breakdown", result.withDescriptions());
            w.println();
            writeDetailedFailures(w, "A failed questions", result.schemaOnly());
            w.println();
            writeDetailedFailures(w, "B failed questions", result.withDescriptions());
            w.println();
            writeDimensionDelta(w, result);
        }
    }

    private static void writeFailureBreakdown(PrintWriter w, String title, ArmResult arm) {
        w.println(title + ":");
        for (Phase1FailureClass fc : Phase1FailureClass.values()) {
            if (fc == Phase1FailureClass.NONE) continue;
            int n = arm.failureCounts().getOrDefault(fc, 0);
            if (n > 0) w.printf("  %s: %d%n", fc, n);
        }
        if (!arm.runtimeFailures().isEmpty()) {
            w.println("  runtime_errors: " + arm.runtimeFailures().size());
        }
    }

    private static void writeDetailedFailures(PrintWriter w, String title, ArmResult arm) {
        w.println(title + ":");
        int n = 0;
        for (Phase1DetailedCaseScore s : arm.cases()) {
            if (s.metricCorrect && s.dimensionCorrect && s.filterCorrect && s.executionSuccess) continue;
            n++;
            w.printf("  [%s] %s%n", s.testCase.datasetId(), s.testCase.question());
            w.printf("    expected: metric=%s dim=%s filters=%s%n",
                    s.testCase.expectedMetric(), s.testCase.expectedDimension(),
                    s.testCase.expectedFilters());
            w.printf("    resolved: metric=%s dim=%s agg=%s order=%s conf=%.2f%n",
                    s.resolvedMetric, s.resolvedDimension, s.resolvedAggregation,
                    s.resolvedOrderDirection, s.confidence);
            for (var f : s.failures) {
                w.printf("    -> %s: %s%n", f.failureClass(), f.detail());
            }
        }
        if (n == 0) w.println("  (none)");
    }

    private static void writeDimensionDelta(PrintWriter w, AbComparison result) {
        w.println("Dimension errors fixed by descriptions (A wrong, B correct):");
        int fixed = 0;
        for (int i = 0; i < result.schemaOnly().cases().size(); i++) {
            var a = result.schemaOnly().cases().get(i);
            var b = result.withDescriptions().cases().get(i);
            if (!a.dimensionCorrect && b.dimensionCorrect) {
                fixed++;
                w.printf("  [%s] %s%n", a.testCase.datasetId(), a.testCase.question());
            }
        }
        w.println("Total dimension fixes: " + fixed);
        w.println();
        w.println("Dimension errors introduced by descriptions (A correct, B wrong):");
        int regressed = 0;
        for (int i = 0; i < result.schemaOnly().cases().size(); i++) {
            var a = result.schemaOnly().cases().get(i);
            var b = result.withDescriptions().cases().get(i);
            if (a.dimensionCorrect && !b.dimensionCorrect) {
                regressed++;
                w.printf("  [%s] %s%n", b.testCase.datasetId(), b.testCase.question());
            }
        }
        w.println("Total dimension regressions: " + regressed);
    }

    private static Phase1DetailedCaseScore failedScore(Phase1FactualCase c, String msg) {
        return new Phase1DetailedCaseScore(
                c, false, false, false, false,
                List.of(new Phase1FailureClassifier.FailureDetail(
                        Phase1FailureClass.EXECUTION_FAILED, msg)),
                null, null, null, null, "", 0, "");
    }

    public static int resolveBenchmarkLimit(int defaultLimit) {
        String env = System.getenv("PHASE1_BENCHMARK_MAX");
        if (env != null && !env.isBlank()) {
            try {
                return Integer.parseInt(env.trim());
            } catch (NumberFormatException ignored) { }
        }
        return defaultLimit;
    }
}
