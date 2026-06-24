package com.example.BACKEND.catalogue.decision.governance;

import com.example.BACKEND.catalogue.decision.findings.AnalyticalFinding;
import com.example.BACKEND.catalogue.decision.findings.AnalyticalFinding.*;
import com.example.BACKEND.catalogue.decision.governance.MetricSemanticTypes.AggregationType;
import com.example.BACKEND.catalogue.decision.governance.MetricSemanticTypes.MetricUnit;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Prevents invalid aggregation operations: averaging averages, summing ratios,
 * ranking percentages without denominator, comparing incompatible metrics.
 */
@Component
public class AggregationValidityEngine {

    private final MetricSemanticRegistry registry;

    public AggregationValidityEngine(MetricSemanticRegistry registry) {
        this.registry = registry;
    }

    public ValidationResult validate(AnalyticalFinding finding) {
        List<String> violations = new ArrayList<>();
        if (finding == null) return new ValidationResult(false, violations);

        switch (finding) {
            case ContributionFinding c -> violations.addAll(validateContribution(c));
            case RankingFinding r -> violations.addAll(validateRanking(r));
            case ComparativeFinding c -> violations.addAll(validateComparative(c));
            case EfficiencyFinding e -> violations.addAll(validateEfficiency(e));
            case TemporalPatternFinding t -> violations.addAll(validateTemporal(t));
            case CorrelationFinding c -> { /* correlation uses CORR aggregate — no grouping validation */ }
        }

        return new ValidationResult(violations.isEmpty(), violations);
    }

    private List<String> validateContribution(ContributionFinding c) {
        List<String> v = new ArrayList<>();
        var def = registry.resolve(c.metricLabel());
        if (def.isEmpty()) return v;

        if (def.get().isRatioOrRate()) {
            v.add("Cannot compute contribution shares on ratio/rate metric: " + c.metricLabel());
        }
        if (!def.get().isFullyAdditive()) {
            v.add("Contribution requires SUM-aggregatable metric");
        }
        if (!def.get().allowsGrouping(c.dimensionLabel())) {
            v.add("Grouping dimension not valid for metric: " + c.dimensionLabel());
        }
        return v;
    }

    private List<String> validateRanking(RankingFinding r) {
        List<String> v = new ArrayList<>();
        var def = registry.resolve(r.metricLabel());
        if (def.isEmpty()) return v;

        if (!MetricSemanticDefinition.RANKABLE_AGGREGATIONS.contains(def.get().aggregationType())) {
            v.add("Metric aggregation not rankable: " + def.get().aggregationType());
        }
        if (def.get().aggregationType() == AggregationType.RATIO && r.rankedEntities().size() < 3) {
            v.add("Ranking ratios requires sufficient denominator support across entities");
        }
        if (def.get().unit() == MetricUnit.PERCENT
                && !def.get().requiresDenominatorForShare()) {
            v.add("Ranking percentages without explicit denominator support");
        }
        return v;
    }

    private List<String> validateComparative(ComparativeFinding c) {
        List<String> v = new ArrayList<>();
        var def = registry.resolve(c.metricLabel());
        if (def.isEmpty()) return v;
        if (c.valueA() < 0 || c.valueB() < 0) {
            v.add("Negative values in comparison for " + def.get().businessMeaning());
        }
        return v;
    }

    private List<String> validateEfficiency(EfficiencyFinding e) {
        List<String> v = new ArrayList<>();
        var num = registry.resolve(e.numeratorLabel());
        var denom = registry.resolve(e.denominatorLabel());
        if (num.isPresent() && denom.isPresent()
                && num.get().aggregationType() == AggregationType.AVG
                && denom.get().aggregationType() == AggregationType.AVG) {
            v.add("Averaging averages in efficiency ratio");
        }
        if (e.entries().stream().anyMatch(en -> en.efficiencyRatio() < 0)) {
            v.add("Negative efficiency ratio detected");
        }
        return v;
    }

    private List<String> validateTemporal(TemporalPatternFinding t) {
        List<String> v = new ArrayList<>();
        if (t.periods() != null && t.periods().size() >= 2) {
            double first = t.periods().getFirst().value();
            double last = t.periods().getLast().value();
            if (first == last && t.periods().size() > 3) {
                v.add("Flat temporal series — trend claims unsupported");
            }
        }
        return v;
    }

    public boolean areMetricsCompatible(String metricA, String metricB) {
        var a = registry.resolve(metricA);
        var b = registry.resolve(metricB);
        if (a.isEmpty() || b.isEmpty()) return true;
        return a.get().businessMeaning() == b.get().businessMeaning()
                || a.get().unit() == b.get().unit();
    }

    public record ValidationResult(boolean valid, List<String> violations) {}
}
