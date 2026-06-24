package com.example.BACKEND.experiment.phase1.benchmark;

import com.example.BACKEND.experiment.phase1.Phase1PlannerOutput;

import java.util.List;

public final class Phase1DetailedCaseScore {

    public final Phase1FactualCase testCase;
    public final boolean metricCorrect;
    public final boolean dimensionCorrect;
    public final boolean filterCorrect;
    public final boolean executionSuccess;
    public final List<Phase1FailureClassifier.FailureDetail> failures;
    public final String resolvedMetric;
    public final String resolvedDimension;
    public final String resolvedAggregation;
    public final String resolvedOrderDirection;
    public final String sqlSnippet;
    public final double confidence;
    public final String reasoning;

    public Phase1DetailedCaseScore(
            Phase1FactualCase testCase,
            boolean metricCorrect,
            boolean dimensionCorrect,
            boolean filterCorrect,
            boolean executionSuccess,
            List<Phase1FailureClassifier.FailureDetail> failures,
            String resolvedMetric,
            String resolvedDimension,
            String resolvedAggregation,
            String resolvedOrderDirection,
            String sqlSnippet,
            double confidence,
            String reasoning
    ) {
        this.testCase = testCase;
        this.metricCorrect = metricCorrect;
        this.dimensionCorrect = dimensionCorrect;
        this.filterCorrect = filterCorrect;
        this.executionSuccess = executionSuccess;
        this.failures = failures;
        this.resolvedMetric = resolvedMetric;
        this.resolvedDimension = resolvedDimension;
        this.resolvedAggregation = resolvedAggregation;
        this.resolvedOrderDirection = resolvedOrderDirection;
        this.sqlSnippet = sqlSnippet;
        this.confidence = confidence;
        this.reasoning = reasoning;
    }

    public static Phase1DetailedCaseScore score(Phase1FactualCase c, Phase1PlannerOutput out) {
        String metric = out != null ? out.metric() : null;
        String dim = out != null && out.dimensions() != null && !out.dimensions().isEmpty()
                ? out.dimensions().get(0) : null;
        String agg = out != null ? out.aggregation() : null;
        String orderDir = out != null && out.ordering() != null ? out.ordering().direction() : null;
        String sql = out != null && out.querySpecs() != null && !out.querySpecs().isEmpty()
                ? out.querySpecs().get(0).sql() : "";
        List<Phase1FailureClassifier.FailureDetail> failures =
                Phase1FailureClassifier.classify(c, out);

        return new Phase1DetailedCaseScore(
                c,
                failures.stream().noneMatch(f -> f.failureClass() == Phase1FailureClass.WRONG_METRIC),
                failures.stream().noneMatch(f -> f.failureClass() == Phase1FailureClass.WRONG_DIMENSION),
                failures.stream().noneMatch(f -> f.failureClass() == Phase1FailureClass.WRONG_FILTER),
                !sql.isBlank(),
                failures.stream().filter(f -> f.failureClass() != Phase1FailureClass.NONE).toList(),
                metric, dim, agg, orderDir,
                truncate(sql),
                out != null ? out.confidence() : 0,
                out != null ? out.reasoning() : "");
    }

    private static String truncate(String sql) {
        if (sql == null) return "";
        return sql.length() > 160 ? sql.substring(0, 160) + "..." : sql;
    }
}
