package com.example.BACKEND.catalogue.decision.validation;

import com.example.BACKEND.catalogue.decision.semantic.SemanticAnalysisPlan;
import com.example.BACKEND.catalogue.decision.semantic.SemanticAnalyticalParser;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Validates semantic parser plan generation against benchmark cases.
 */
@Component
public class SemanticParserValidationHarness {

    private final SemanticAnalyticalParser parser;

    public SemanticParserValidationHarness(SemanticAnalyticalParser parser) {
        this.parser = parser;
    }

    public record CaseResult(
            String  id,
            String  question,
            boolean passed,
            List<String> failures,
            Map<String, Object> resolved
    ) {}

    public record SuiteReport(
            int total,
            int passed,
            int failed,
            List<CaseResult> results
    ) {
        public double passRate() {
            return total == 0 ? 0 : (double) passed / total;
        }
    }

    public SuiteReport runAll() {
        List<CaseResult> results = new ArrayList<>();
        int passed = 0;
        for (SemanticParserBenchmarkCase tc : SemanticParserBenchmarkSuite.all()) {
            CaseResult r = runCase(tc);
            results.add(r);
            if (r.passed()) passed++;
        }
        return new SuiteReport(results.size(), passed, results.size() - passed, results);
    }

    public CaseResult runCase(SemanticParserBenchmarkCase tc) {
        SemanticAnalysisPlan plan = parser.parse(tc.question(), null);
        List<String> failures = new ArrayList<>();
        Map<String, Object> resolved = new LinkedHashMap<>();

        resolved.put("parsed", plan.parsed());
        resolved.put("primaryMetric", plan.primaryMetric());
        resolved.put("secondaryMetric", plan.secondaryMetric());
        resolved.put("grouping", plan.groupingDimension());
        resolved.put("intent", plan.intent() != null ? plan.intent().name() : null);
        resolved.put("pattern", plan.patternKind() != null ? plan.patternKind().name() : null);
        resolved.put("planSummary", plan.planSummary());

        if (!plan.parsed()) {
            failures.add("Parser failed: " + plan.failureReason());
        }
        if (!Objects.equals(tc.expectedPrimaryMetric(), plan.primaryMetric())) {
            failures.add(String.format(Locale.ROOT,
                    "Expected primary metric %s but got %s",
                    tc.expectedPrimaryMetric(), plan.primaryMetric()));
        }
        if (tc.expectedIntent() != null && plan.intent() != null
                && tc.expectedIntent() != plan.intent().canonical()) {
            failures.add(String.format(Locale.ROOT,
                    "Expected intent %s but got %s",
                    tc.expectedIntent(), plan.intent()));
        }
        if (tc.expectCompositionRatio()) {
            if (!plan.isCompositionRatio()) {
                failures.add("Expected composition ratio plan");
            }
            if (plan.contributionPlan() == null) {
                failures.add("Missing contribution plan");
            } else if (!Objects.equals(tc.expectedDenominatorMetric(),
                    plan.contributionPlan().denominatorMetric())) {
                failures.add(String.format(Locale.ROOT,
                        "Expected denominator %s but got %s",
                        tc.expectedDenominatorMetric(),
                        plan.contributionPlan().denominatorMetric()));
            }
        } else if (tc.expectedGrouping() != null) {
            if (plan.groupingDimension() == null
                    || !plan.groupingDimension().contains(tc.expectedGrouping().replace("_bucket", ""))) {
                failures.add(String.format(Locale.ROOT,
                        "Expected grouping containing %s but got %s",
                        tc.expectedGrouping(), plan.groupingDimension()));
            }
        }

        return new CaseResult(tc.id(), tc.question(), failures.isEmpty(), failures, resolved);
    }
}
