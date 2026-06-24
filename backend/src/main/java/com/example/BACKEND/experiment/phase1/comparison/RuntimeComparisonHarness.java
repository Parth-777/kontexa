package com.example.BACKEND.experiment.phase1.comparison;

import com.example.BACKEND.experiment.phase1.Phase1CatalogueFactory;
import com.example.BACKEND.experiment.phase1.Phase1CataloguePayloadMode;
import com.example.BACKEND.experiment.phase1.Phase1DatasetRegistry;
import com.example.BACKEND.experiment.phase1.Phase1LlmPlannerExperiment;
import com.example.BACKEND.experiment.phase1.Phase1PlannerInput;
import com.example.BACKEND.experiment.phase1.benchmark.Phase1CurrentPipelineRunner;

import java.util.ArrayList;
import java.util.List;

/**
 * Runs production pipeline and GPT+catalogue planner on the same questions.
 */
public final class RuntimeComparisonHarness {

    private final Phase1CurrentPipelineRunner production;
    private final Phase1LlmPlannerExperiment gptPlanner;

    public RuntimeComparisonHarness(
            Phase1CurrentPipelineRunner production,
            Phase1LlmPlannerExperiment gptPlanner
    ) {
        this.production = production;
        this.gptPlanner = gptPlanner;
    }

    public record SideBySideCase(
            int index,
            RuntimeComparisonQuestion question,
            PlannerRunSnapshot production,
            PlannerRunSnapshot gptCatalogue
    ) {
        public boolean productionSucceeded() { return production.executionSuccess(); }
        public boolean gptSucceeded() { return gptCatalogue.executionSuccess(); }

        public String winnerHint() {
            if (productionSucceeded() && !gptSucceeded()) return "production";
            if (gptSucceeded() && !productionSucceeded()) return "gpt_catalogue";
            if (productionSucceeded() && gptSucceeded()) {
                if (sameMetric() && sameDimension()) return "tie";
                return "both_sql_differ";
            }
            return "neither";
        }

        private boolean sameMetric() {
            return eq(production.metric(), gptCatalogue.metric());
        }

        private boolean sameDimension() {
            return eq(production.dimension(), gptCatalogue.dimension());
        }

        private static boolean eq(String a, String b) {
            if (a == null && b == null) return true;
            if (a == null || b == null) return false;
            return a.equalsIgnoreCase(b);
        }
    }

    public record ComparisonRun(List<SideBySideCase> cases, List<String> runtimeNotes) {}

    public ComparisonRun run(List<RuntimeComparisonQuestion> questions, long gptSleepMs) {
        List<SideBySideCase> cases = new ArrayList<>();
        List<String> notes = new ArrayList<>();

        for (int i = 0; i < questions.size(); i++) {
            RuntimeComparisonQuestion q = questions.get(i);
            var dataset = Phase1DatasetRegistry.get(q.datasetId());
            var bundle = dataset.bundle();

            PlannerRunSnapshot prodSnap;
            try {
                prodSnap = PlannerRunSnapshot.fromCurrent(production.run(q.question(), bundle));
            } catch (Exception e) {
                String msg = e.getClass().getSimpleName() + ": " + e.getMessage();
                notes.add("PROD case " + (i + 1) + ": " + msg);
                prodSnap = PlannerRunSnapshot.fromCurrentError(msg);
            }

            PlannerRunSnapshot gptSnap;
            try {
                var input = new Phase1PlannerInput(
                        q.question(),
                        Phase1CatalogueFactory.catalogueFrom(
                                q.datasetId(), bundle, Phase1CataloguePayloadMode.WITH_DESCRIPTIONS),
                        Phase1CatalogueFactory.schemaFrom(bundle));
                gptSnap = PlannerRunSnapshot.fromLlm(gptPlanner.plan(input, bundle));
                if (gptSleepMs > 0) Thread.sleep(gptSleepMs);
            } catch (Exception e) {
                String msg = e.getClass().getSimpleName() + ": " + e.getMessage();
                notes.add("GPT case " + (i + 1) + " [" + q.datasetId() + "]: " + msg);
                gptSnap = PlannerRunSnapshot.fromLlmError(msg);
            }

            cases.add(new SideBySideCase(i + 1, q, prodSnap, gptSnap));
            if ((i + 1) % 10 == 0) {
                System.out.printf("[runtime-comparison] progress %d/%d%n", i + 1, questions.size());
            }
        }
        return new ComparisonRun(cases, notes);
    }

    public static int resolveQuestionLimit(int defaultLimit) {
        String env = System.getenv("RUNTIME_COMPARISON_MAX");
        if (env != null && !env.isBlank()) {
            try {
                return Integer.parseInt(env.trim());
            } catch (NumberFormatException ignored) { }
        }
        return defaultLimit;
    }
}
