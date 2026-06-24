package com.example.BACKEND.experiment.phase1.benchmark;

import com.example.BACKEND.experiment.phase1.Phase1FilterSpec;
import com.example.BACKEND.experiment.phase1.Phase1OrderingSpec;
import com.example.BACKEND.experiment.phase1.Phase1PlannerOutput;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Classifies GPT planner failures for detailed benchmark reporting.
 */
public final class Phase1FailureClassifier {

    private Phase1FailureClassifier() {}

    public record FailureDetail(
            Phase1FailureClass failureClass,
            String detail
    ) {}

    public static List<FailureDetail> classify(Phase1FactualCase c, Phase1PlannerOutput out) {
        List<FailureDetail> failures = new ArrayList<>();
        if (out == null) {
            failures.add(new FailureDetail(Phase1FailureClass.EXECUTION_FAILED, "null output"));
            return failures;
        }

        String metric = out.metric();
        String dim = out.dimensions() != null && !out.dimensions().isEmpty()
                ? out.dimensions().get(0) : null;
        boolean execOk = out.querySpecs() != null && !out.querySpecs().isEmpty()
                && out.querySpecs().get(0).sql() != null
                && !out.querySpecs().get(0).sql().isBlank();

        if (!eq(metric, c.expectedMetric())) {
            failures.add(new FailureDetail(Phase1FailureClass.WRONG_METRIC,
                    "expected=" + c.expectedMetric() + " got=" + metric));
        }
        if (!eqDim(dim, c.expectedDimension())) {
            failures.add(new FailureDetail(Phase1FailureClass.WRONG_DIMENSION,
                    "expected=" + c.expectedDimension() + " got=" + dim));
        }
        if (!filtersMatch(out.filters(), c.expectedFilters())) {
            failures.add(new FailureDetail(Phase1FailureClass.WRONG_FILTER,
                    "expected=" + c.expectedFilters() + " got=" + out.filters()));
        }
        if (c.expectedAggregation() != null && out.aggregation() != null
                && !c.expectedAggregation().equalsIgnoreCase(out.aggregation())) {
            // AVG questions may still use SUM in SQL template — only flag explicit AVG expectation
            if ("AVG".equalsIgnoreCase(c.expectedAggregation())
                    && !"AVG".equalsIgnoreCase(out.aggregation())) {
                failures.add(new FailureDetail(Phase1FailureClass.WRONG_AGGREGATION,
                        "expected=" + c.expectedAggregation() + " got=" + out.aggregation()));
            }
        }
        if (c.expectedOrderDirection() != null) {
            Phase1OrderingSpec ord = out.ordering();
            String got = ord != null ? ord.direction() : null;
            if (!c.expectedOrderDirection().equalsIgnoreCase(got)) {
                failures.add(new FailureDetail(Phase1FailureClass.WRONG_RANKING_DIRECTION,
                        "expected=" + c.expectedOrderDirection() + " got=" + got));
            }
        }
        if (c.expectedIntent() != null && out.intent() != null
                && out.intent() != c.expectedIntent()
                && c.expectedIntent() != com.example.BACKEND.catalogue.decision.planning.AnalysisIntent.CONTRIBUTION) {
            failures.add(new FailureDetail(Phase1FailureClass.WRONG_INTERPRETATION,
                    "expected_intent=" + c.expectedIntent() + " got=" + out.intent()));
        }
        if (!execOk && c.expectSql()) {
            failures.add(new FailureDetail(Phase1FailureClass.EXECUTION_FAILED, "no SQL produced"));
        }
        if (failures.isEmpty()) {
            failures.add(new FailureDetail(Phase1FailureClass.NONE, ""));
        }
        return failures;
    }

    public static boolean isSuccess(List<FailureDetail> details) {
        return details.stream().allMatch(d -> d.failureClass() == Phase1FailureClass.NONE);
    }

    private static boolean eq(String a, String b) {
        if (b == null) return a == null || a.isBlank();
        return a != null && a.equalsIgnoreCase(b);
    }

    private static boolean eqDim(String a, String b) {
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

    private static String truncate(String s) {
        if (s == null) return "";
        return s.length() > 80 ? s.substring(0, 80) + "..." : s;
    }
}
