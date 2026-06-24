package com.example.BACKEND.experiment.phase1.comparison;

import com.example.BACKEND.experiment.phase1.Phase1FilterSpec;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

/**
 * Writes side-by-side planner comparison reports.
 */
public final class RuntimeComparisonReportWriter {

    private RuntimeComparisonReportWriter() {}

    public static void write(RuntimeComparisonHarness.ComparisonRun run, Path outFile) throws IOException {
        Files.createDirectories(outFile.getParent());
        try (PrintWriter w = new PrintWriter(Files.newBufferedWriter(outFile))) {
            w.println("Runtime Planner Comparison — " + Instant.now());
            w.println("A = Current production pipeline (deterministic semantics)");
            w.println("B = GPT + approved catalogue descriptions");
            w.println("Questions: " + run.cases().size() + " (human-authored, unseen)");
            w.println();

            writeSummary(w, run);
            w.println();
            w.println("=".repeat(100));
            w.println("SIDE-BY-SIDE CASES");
            w.println("=".repeat(100));

            for (var c : run.cases()) {
                writeCase(w, c);
            }

            if (!run.runtimeNotes().isEmpty()) {
                w.println();
                w.println("RUNTIME NOTES:");
                run.runtimeNotes().forEach(n -> w.println("  " + n));
            }
        }
    }

    private static void writeSummary(PrintWriter w, RuntimeComparisonHarness.ComparisonRun run) {
        int n = run.cases().size();
        int prodOk = (int) run.cases().stream().filter(RuntimeComparisonHarness.SideBySideCase::productionSucceeded).count();
        int gptOk = (int) run.cases().stream().filter(RuntimeComparisonHarness.SideBySideCase::gptSucceeded).count();
        int bothOk = (int) run.cases().stream()
                .filter(c -> c.productionSucceeded() && c.gptSucceeded()).count();
        int prodOnly = (int) run.cases().stream()
                .filter(c -> c.productionSucceeded() && !c.gptSucceeded()).count();
        int gptOnly = (int) run.cases().stream()
                .filter(c -> c.gptSucceeded() && !c.productionSucceeded()).count();
        int neither = (int) run.cases().stream()
                .filter(c -> !c.productionSucceeded() && !c.gptSucceeded()).count();
        int metricAgree = (int) run.cases().stream()
                .filter(c -> c.productionSucceeded() && c.gptSucceeded()
                        && eq(c.production().metric(), c.gptCatalogue().metric())).count();
        int dimAgree = (int) run.cases().stream()
                .filter(c -> c.productionSucceeded() && c.gptSucceeded()
                        && eq(c.production().dimension(), c.gptCatalogue().dimension())).count();

        w.println("SUMMARY");
        w.println("  Production execution success: " + prodOk + "/" + n);
        w.println("  GPT+catalogue execution success: " + gptOk + "/" + n);
        w.println("  Both succeeded:               " + bothOk + "/" + n);
        w.println("  Production only:              " + prodOnly);
        w.println("  GPT only:                     " + gptOnly);
        w.println("  Neither:                      " + neither);
        w.println("  Metric agreement (both OK):   " + metricAgree + "/" + bothOk);
        w.println("  Dimension agreement (both OK):" + dimAgree + "/" + bothOk);
        w.println();
        w.printf("  DELTA execution (GPT - production): %+d%n", gptOk - prodOk);
    }

    private static void writeCase(PrintWriter w, RuntimeComparisonHarness.SideBySideCase c) {
        w.println();
        w.println("-".repeat(100));
        w.printf("CASE %d | dataset: %s%n", c.index(), c.question().datasetId());
        w.println("QUESTION: " + c.question().question());
        w.println("OUTCOME HINT: " + c.winnerHint());
        w.println();
        writePlanner(w, "A) PRODUCTION PIPELINE", c.production());
        w.println();
        writePlanner(w, "B) GPT + CATALOGUE", c.gptCatalogue());
    }

    private static void writePlanner(PrintWriter w, String title, PlannerRunSnapshot s) {
        w.println(title);
        w.println("  Metric:      " + nv(s.metric()));
        w.println("  Dimension:   " + nv(s.dimension()));
        w.println("  Filters:     " + formatFilters(s.filters()));
        w.println("  Execution:   " + s.executionResult());
        w.println("  Final answer:" + s.finalAnswer());
        if (s.notes() != null && !s.notes().isBlank()) {
            w.println("  Notes:       " + s.notes());
        }
        w.println("  SQL:");
        String sql = s.sql() != null ? s.sql() : "";
        if (sql.isBlank()) {
            w.println("    (none)");
        } else {
            for (String line : sql.split("\n")) {
                w.println("    " + line);
            }
        }
    }

    private static String formatFilters(List<Phase1FilterSpec> filters) {
        if (filters == null || filters.isEmpty()) return "(none)";
        return filters.stream()
                .map(f -> f.column() + " " + f.operator() + " " + f.value())
                .collect(Collectors.joining(", "));
    }

    private static String nv(String s) {
        return s == null || s.isBlank() ? "(null)" : s;
    }

    private static boolean eq(String a, String b) {
        if (a == null && b == null) return true;
        if (a == null || b == null) return false;
        return a.equalsIgnoreCase(b);
    }
}
