package com.example.BACKEND.experiment.phase1.benchmark;

import com.example.BACKEND.catalogue.decision.contracts.DecisionModels.QuerySpec;
import com.example.BACKEND.experiment.phase1.Phase1FilterSpec;
import com.example.BACKEND.experiment.phase1.Phase1PlannerOutput;

import java.util.List;
import java.util.Locale;
import java.util.Objects;

public final class Phase1CaseScore {

    public final Phase1FactualCase testCase;
    public final boolean metricCorrect;
    public final boolean dimensionCorrect;
    public final boolean filterCorrect;
    public final boolean sqlCorrect;
    public final boolean executionSuccess;
    public final String resolvedMetric;
    public final String resolvedDimension;
    public final String sqlSnippet;
    public final String notes;

    public Phase1CaseScore(
            Phase1FactualCase testCase,
            boolean metricCorrect,
            boolean dimensionCorrect,
            boolean filterCorrect,
            boolean sqlCorrect,
            boolean executionSuccess,
            String resolvedMetric,
            String resolvedDimension,
            String sqlSnippet,
            String notes
    ) {
        this.testCase = testCase;
        this.metricCorrect = metricCorrect;
        this.dimensionCorrect = dimensionCorrect;
        this.filterCorrect = filterCorrect;
        this.sqlCorrect = sqlCorrect;
        this.executionSuccess = executionSuccess;
        this.resolvedMetric = resolvedMetric;
        this.resolvedDimension = resolvedDimension;
        this.sqlSnippet = sqlSnippet;
        this.notes = notes;
    }

    public static Phase1CaseScore scoreLlm(Phase1FactualCase c, Phase1PlannerOutput out) {
        String metric = out.metric();
        String dim = out.dimensions() != null && !out.dimensions().isEmpty()
                ? out.dimensions().get(0) : null;
        List<Phase1FilterSpec> filters = out.filters() != null ? out.filters() : List.of();
        String sql = out.querySpecs() != null && !out.querySpecs().isEmpty()
                ? out.querySpecs().get(0).sql() : "";

        return new Phase1CaseScore(
                c,
                eq(metric, c.expectedMetric()),
                eqDim(dim, c.expectedDimension()),
                filtersMatch(filters, c.expectedFilters()),
                sqlValid(c, sql, metric, dim, filters),
                !sql.isBlank(),
                metric, dim, truncate(sql), "");
    }

    public static Phase1CaseScore scoreCurrent(Phase1FactualCase c, Phase1CurrentPipelineRunner.Phase1PipelineRun run) {
        String sql = run.querySpecs() != null && !run.querySpecs().isEmpty()
                ? run.querySpecs().get(0).sql() : "";
        return new Phase1CaseScore(
                c,
                eq(run.metric(), c.expectedMetric()),
                eqDim(run.dimension(), c.expectedDimension()),
                c.expectedFilters().isEmpty(),
                sqlValid(c, sql, run.metric(), run.dimension(), List.of()),
                run.executable() && !sql.isBlank(),
                run.metric(), run.dimension(), truncate(sql),
                run.executable() ? "" : "plan blocked");
    }

    private static boolean eq(String a, String b) {
        if (b == null) return a == null;
        return a != null && a.equalsIgnoreCase(b);
    }

    private static boolean eqDim(String a, String b) {
        if (b == null || b.isBlank()) return a == null || a.isBlank();
        return eq(a, b);
    }

    private static boolean filtersMatch(List<Phase1FilterSpec> actual, List<Phase1FilterSpec> expected) {
        if (expected == null || expected.isEmpty()) return true;
        if (actual == null || actual.isEmpty()) return false;
        for (Phase1FilterSpec e : expected) {
            boolean found = actual.stream().anyMatch(a ->
                    eq(a.column(), e.column())
                            && Objects.equals(norm(a.operator()), norm(e.operator()))
                            && Objects.equals(norm(a.value()), norm(e.value())));
            if (!found) return false;
        }
        return true;
    }

    private static String norm(String s) {
        return s == null ? "" : s.trim().toLowerCase(Locale.ROOT);
    }

    private static boolean sqlValid(
            Phase1FactualCase c, String sql, String metric, String dim, List<Phase1FilterSpec> filters
    ) {
        if (!c.expectSql()) return true;
        if (sql == null || sql.isBlank()) return false;
        String upper = sql.toUpperCase(Locale.ROOT);
        if (!upper.contains("SELECT") || !upper.contains("FROM")) return false;
        if (metric != null && !upper.contains(metric.toUpperCase(Locale.ROOT))) return false;
        if (dim != null && !dim.isBlank()) {
            if (!upper.contains("GROUP BY") || !upper.contains(dim.toUpperCase(Locale.ROOT))) {
                // scalar filtered queries have no GROUP BY
                if (filters == null || filters.isEmpty()) return false;
            }
        }
        for (Phase1FilterSpec f : filters) {
            if (f.column() != null && !upper.contains(f.column().toUpperCase(Locale.ROOT))) return false;
        }
        return true;
    }

    private static String truncate(String sql) {
        if (sql == null) return "";
        return sql.length() > 200 ? sql.substring(0, 200) + "..." : sql;
    }
}
