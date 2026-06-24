package com.example.BACKEND.catalogue.decision.clarification;

import com.example.BACKEND.catalogue.decision.contracts.DecisionModels.DimensionDescriptor;
import com.example.BACKEND.catalogue.decision.contracts.DecisionModels.RegistryResolutionBundle;
import com.example.BACKEND.catalogue.decision.governance.MetricSemanticTypes.AggregationType;
import com.example.BACKEND.catalogue.decision.planning.AnalyticalIntentType;
import com.example.BACKEND.catalogue.decision.semantic.SemanticAnalysisPlan;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Validates query viability before full warehouse execution.
 */
@Component
public class QueryViabilityChecker {

    private final MetricFallbackHierarchy fallback;

    public QueryViabilityChecker(MetricFallbackHierarchy fallback) {
        this.fallback = fallback;
    }

    public ViabilityResult check(AnalyticalAssumption assumption, RegistryResolutionBundle bundle) {
        return check(assumption, bundle, null);
    }

    public ViabilityResult check(
            AnalyticalAssumption assumption, RegistryResolutionBundle bundle,
            SemanticAnalysisPlan semantic
    ) {
        List<String> issues = new ArrayList<>();

        if (bundle == null || bundle.entities() == null || bundle.entities().isEmpty()) {
            issues.add("No dataset entities available for this tenant");
        }
        if (bundle == null || bundle.metrics() == null || bundle.metrics().isEmpty()) {
            issues.add("No metrics registered for this analytical objective");
        }

        String metric = assumption.primaryMetric();
        if (!fallback.metricAvailable(metric, bundle)) {
            String resolved = fallback.resolve(metric, bundle);
            if (!fallback.metricAvailable(resolved, bundle)) {
                issues.add("Required metric not available: " + metric);
            }
        }

        boolean compositionRatio = semantic != null && semantic.isCompositionRatio();
        if (!compositionRatio && !hasValidGrouping(assumption.grouping(), bundle)) {
            issues.add("Grouping dimension not found: " + assumption.grouping());
        }

        if (!aggregationValid(assumption.aggregation(), assumption.intent(), compositionRatio)) {
            issues.add("Invalid aggregation " + assumption.aggregation()
                    + " for intent " + assumption.intent());
        }

        if (assumption.intent() == AnalyticalIntentType.TREND_ANALYSIS
                && !hasTemporalDimension(bundle)) {
            issues.add("Trend analysis requires a temporal field — none found in schema");
        }

        return new ViabilityResult(issues.isEmpty(), issues);
    }

    private boolean hasValidGrouping(String grouping, RegistryResolutionBundle bundle) {
        if (grouping == null || grouping.isBlank()) return false;
        return groupingExists(grouping, bundle);
    }

    private boolean groupingExists(String grouping, RegistryResolutionBundle bundle) {
        if (grouping == null || grouping.isBlank()) return false;
        if (bundle == null) return true;
        String norm = grouping.toLowerCase(Locale.ROOT);
        if (bundle.dimensions() == null || bundle.dimensions().isEmpty()) {
            return norm.contains("distance") || norm.contains("hour") || norm.contains("zone");
        }
        return bundle.dimensions().stream().anyMatch(d -> matchesDimension(d, norm));
    }

    private boolean matchesDimension(DimensionDescriptor d, String norm) {
        String key = d.key() != null ? d.key().toLowerCase(Locale.ROOT) : "";
        String expr = d.expression() != null ? d.expression().toLowerCase(Locale.ROOT) : "";
        return key.contains(norm) || norm.contains(key) || expr.contains(norm)
                || (norm.contains("distance") && (key.contains("distance") || expr.contains("distance")))
                || (norm.contains("bucket") && key.contains("distance"));
    }

    private boolean hasTemporalDimension(RegistryResolutionBundle bundle) {
        if (bundle == null || bundle.dimensions() == null) return false;
        return bundle.dimensions().stream().anyMatch(d -> {
            String k = d.key() != null ? d.key().toLowerCase(Locale.ROOT) : "";
            String t = d.type() != null ? d.type().toLowerCase(Locale.ROOT) : "";
            return k.contains("time") || k.contains("date") || k.contains("pickup")
                    || k.contains("dropoff") || t.contains("time") || t.contains("date");
        });
    }

    private boolean aggregationValid(AggregationType agg, AnalyticalIntentType intent, boolean compositionRatio) {
        if (compositionRatio) return agg == AggregationType.RATIO;
        return switch (intent) {
            case EFFICIENCY -> agg == AggregationType.RATIO || agg == AggregationType.RATE;
            case COMPOSITION -> agg == AggregationType.SUM || agg == AggregationType.RATIO
                    || agg == AggregationType.COUNT;
            case CONTRIBUTION, DISTRIBUTION -> agg == AggregationType.SUM
                    || agg == AggregationType.COUNT;
            case TREND_ANALYSIS -> agg == AggregationType.SUM || agg == AggregationType.AVG;
            default -> true;
        };
    }

    public record ViabilityResult(boolean viable, List<String> issues) {}
}
