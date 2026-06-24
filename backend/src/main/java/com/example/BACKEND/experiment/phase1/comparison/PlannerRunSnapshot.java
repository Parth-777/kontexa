package com.example.BACKEND.experiment.phase1.comparison;

import com.example.BACKEND.catalogue.decision.contracts.DecisionModels.QuerySpec;
import com.example.BACKEND.experiment.phase1.Phase1FilterSpec;
import com.example.BACKEND.experiment.phase1.Phase1PlannerOutput;
import com.example.BACKEND.experiment.phase1.benchmark.Phase1CurrentPipelineRunner;

import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

/**
 * Normalized capture of one planner run for side-by-side comparison.
 */
public record PlannerRunSnapshot(
        String plannerName,
        String metric,
        String dimension,
        List<Phase1FilterSpec> filters,
        String sql,
        String executionResult,
        boolean executionSuccess,
        String finalAnswer,
        String notes
) {
    public static PlannerRunSnapshot fromCurrent(Phase1CurrentPipelineRunner.Phase1PipelineRun run) {
        String sql = firstSql(run.querySpecs());
        List<Phase1FilterSpec> filters = run.filters() != null && !run.filters().isEmpty()
                ? run.filters()
                : SqlWhereFilterExtractor.extract(sql);
        boolean ok = run.executable() && sql != null && !sql.isBlank();
        String exec = ok ? "SUCCESS" : ("BLOCKED: " + nullTo(run.blockingReason(), "no SQL produced"));
        return new PlannerRunSnapshot(
                "production",
                run.metric(),
                run.dimension(),
                filters,
                sql,
                exec,
                ok,
                formatAnswer(run.metric(), run.dimension(), filters, ok, exec),
                run.executable() ? "" : nullTo(run.blockingReason(), "plan not executable"));
    }

    public static PlannerRunSnapshot fromCurrentError(String message) {
        return error("production", message);
    }

    public static PlannerRunSnapshot fromLlm(Phase1PlannerOutput out) {
        String sql = out != null && out.querySpecs() != null && !out.querySpecs().isEmpty()
                ? out.querySpecs().get(0).sql() : "";
        String metric = out != null ? out.metric() : null;
        String dim = out != null && out.dimensions() != null && !out.dimensions().isEmpty()
                ? out.dimensions().get(0) : null;
        List<Phase1FilterSpec> filters = out != null && out.filters() != null
                ? out.filters() : List.of();
        boolean ok = sql != null && !sql.isBlank();
        String exec = ok ? "SUCCESS" : "BLOCKED: no SQL produced";
        String notes = out != null ? "conf=" + String.format(Locale.ROOT, "%.2f", out.confidence())
                + (out.reasoning() != null && !out.reasoning().isBlank()
                ? " | " + truncate(out.reasoning(), 120) : "") : "";
        return new PlannerRunSnapshot(
                "gpt_catalogue",
                metric,
                dim,
                filters,
                sql,
                exec,
                ok,
                formatAnswer(metric, dim, filters, ok, exec),
                notes);
    }

    public static PlannerRunSnapshot fromLlmError(String message) {
        return error("gpt_catalogue", message);
    }

    private static PlannerRunSnapshot error(String name, String message) {
        return new PlannerRunSnapshot(
                name, null, null, List.of(), "",
                "ERROR: " + message, false,
                "Unable to answer — " + message, message);
    }

    private static String formatAnswer(
            String metric, String dim, List<Phase1FilterSpec> filters,
            boolean ok, String exec
    ) {
        if (!ok) return "Unable to answer — " + exec;
        StringBuilder sb = new StringBuilder("Would return ");
        sb.append(metric != null ? metric : "(unresolved metric)");
        if (dim != null && !dim.isBlank()) {
            sb.append(" grouped by ").append(dim);
        }
        if (filters != null && !filters.isEmpty()) {
            sb.append(" filtered where ").append(formatFilters(filters));
        }
        sb.append(" (query ready)");
        return sb.toString();
    }

    private static String formatFilters(List<Phase1FilterSpec> filters) {
        return filters.stream()
                .map(f -> f.column() + " " + f.operator() + " " + f.value())
                .collect(Collectors.joining(" AND "));
    }

    private static String firstSql(List<QuerySpec> specs) {
        if (specs == null || specs.isEmpty()) return "";
        return specs.get(0).sql() != null ? specs.get(0).sql() : "";
    }

    private static String nullTo(String s, String fallback) {
        return s != null && !s.isBlank() ? s : fallback;
    }

    private static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }
}
