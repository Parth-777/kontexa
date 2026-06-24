package com.example.BACKEND.catalogue.decision.governance;

import com.example.BACKEND.catalogue.decision.governance.MetricSemanticTypes.*;
import com.example.BACKEND.catalogue.decision.planning.AnalyticalIntentType;
import com.example.BACKEND.catalogue.decision.planning.DomainAnalyticalDefaults;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Registry of metric semantic contracts. Every governed metric defines aggregation,
 * additive scope, valid groupings, business meaning, and unit.
 */
@Component
public class MetricSemanticRegistry {

    private final Map<String, MetricSemanticDefinition> byKey;
    private final DomainAnalyticalDefaults domainDefaults;

    public MetricSemanticRegistry(DomainAnalyticalDefaults domainDefaults) {
        this.domainDefaults = domainDefaults;
        this.byKey = buildDefaults();
    }

    public Optional<MetricSemanticDefinition> resolve(String metricKeyOrLabel) {
        if (metricKeyOrLabel == null || metricKeyOrLabel.isBlank()) return Optional.empty();
        String norm = normalize(metricKeyOrLabel);
        if (byKey.containsKey(norm)) return Optional.of(byKey.get(norm));
        for (var e : byKey.entrySet()) {
            if (norm.contains(e.getKey()) || e.getKey().contains(norm)) return Optional.of(e.getValue());
        }
        return Optional.empty();
    }

    public MetricSemanticDefinition require(String metricKeyOrLabel) {
        return resolve(metricKeyOrLabel)
                .orElseGet(() -> genericValueMetric(metricKeyOrLabel));
    }

    public MetricDecompositionBinding bindContributionAnalysis(String question) {
        DomainAnalyticalDefaults.DomainProfile domain = domainDefaults.resolve(question, null);
        MetricSemanticDefinition revenue = require(domain.revenueColumn());
        String grouping = inferDistanceGrouping(question, domain);

        DenominatorContext denom = DenominatorContext.forContribution(
                revenue.displayLabel(), grouping, 0, 0, 0);

        return new MetricDecompositionBinding(
                domain.revenueColumn(),
                revenue.displayLabel(),
                AggregationType.SUM,
                domain.distanceColumn(),
                grouping,
                denom
        );
    }

    public MetricDecompositionBinding bindForIntent(
            String question, AnalyticalIntentType intent, String metricLabel, String groupingLabel
    ) {
        DomainAnalyticalDefaults.DomainProfile domain = domainDefaults.resolve(question, null);
        MetricSemanticDefinition metric = resolve(metricLabel)
                .orElse(resolve(domain.revenueColumn()).orElse(require("total_amount")));

        AggregationType agg = switch (intent) {
            case EFFICIENCY -> AggregationType.RATIO;
            case DISTRIBUTION, SEGMENTATION, CONTRIBUTION, COMPOSITION -> AggregationType.SUM;
            case RANKING -> metric.aggregationType();
            default -> metric.aggregationType();
        };

        String col = metric.metricKey();
        String groupCol = groupingLabel != null && groupingLabel.toLowerCase().contains("distance")
                ? domain.distanceColumn() : col;

        return new MetricDecompositionBinding(
                col, metric.displayLabel(), agg, groupCol, groupingLabel,
                DenominatorContext.forContribution(metric.displayLabel(), groupingLabel, 0, 0, 0)
        );
    }

    private String inferDistanceGrouping(String question, DomainAnalyticalDefaults.DomainProfile domain) {
        String q = question != null ? question.toLowerCase(Locale.ROOT) : "";
        if (q.contains("distance") || q.contains("mile")) return "trip_distance_bucket";
        if (q.contains("hour")) return "pickup_hour";
        if (q.contains("zone")) return "pickup_zone";
        if (q.contains("weekend")) return "weekend_flag";
        return "";
    }

    private MetricSemanticDefinition genericValueMetric(String label) {
        return new MetricSemanticDefinition(
                normalize(label), label != null ? label : "Value",
                AggregationType.SUM, AdditiveScope.FULLY_ADDITIVE,
                List.of("segment", "zone", "hour", "distance", "category"),
                BusinessMeaning.UNKNOWN, MetricUnit.CURRENCY,
                null, null, true
        );
    }

    private Map<String, MetricSemanticDefinition> buildDefaults() {
        return Map.ofEntries(
                entry("total_amount", "Revenue", AggregationType.SUM, AdditiveScope.FULLY_ADDITIVE,
                        List.of("trip_distance", "distance", "bucket", "hour", "zone", "borough", "payment", "vendor", "weekday", "weekend"),
                        BusinessMeaning.REVENUE, MetricUnit.CURRENCY, null, null, true),
                entry("fare_amount", "Fare", AggregationType.SUM, AdditiveScope.FULLY_ADDITIVE,
                        List.of("trip_distance", "distance", "hour", "zone", "payment"),
                        BusinessMeaning.REVENUE, MetricUnit.CURRENCY, null, null, true),
                entry("tip_amount", "Tips", AggregationType.SUM, AdditiveScope.FULLY_ADDITIVE,
                        List.of("payment", "hour", "zone"),
                        BusinessMeaning.REVENUE, MetricUnit.CURRENCY, null, null, false),
                entry("trip_distance", "Trip distance", AggregationType.AVG, AdditiveScope.NON_ADDITIVE,
                        List.of("hour", "zone", "payment"),
                        BusinessMeaning.DISTANCE, MetricUnit.DISTANCE, null, null, false),
                entry("passenger_count", "Passengers", AggregationType.SUM, AdditiveScope.FULLY_ADDITIVE,
                        List.of("hour", "zone", "distance"),
                        BusinessMeaning.COUNT, MetricUnit.COUNT, null, null, false),
                entry("revenue_per_mile", "Revenue per mile", AggregationType.RATIO, AdditiveScope.NON_ADDITIVE,
                        List.of("zone", "hour", "distance"),
                        BusinessMeaning.EFFICIENCY, MetricUnit.RATIO, "total_amount", "trip_distance", false),
                entry("revenue_per_trip", "Revenue per trip", AggregationType.RATIO, AdditiveScope.NON_ADDITIVE,
                        List.of("zone", "hour", "distance", "payment"),
                        BusinessMeaning.EFFICIENCY, MetricUnit.RATIO, "total_amount", "volume", false),
                entry("efficiency_ratio", "Efficiency", AggregationType.RATIO, AdditiveScope.NON_ADDITIVE,
                        List.of("zone", "hour", "distance", "segment"),
                        BusinessMeaning.EFFICIENCY, MetricUnit.RATIO, "total_amount", "volume", false),
                entry("volume", "Trip volume", AggregationType.COUNT, AdditiveScope.FULLY_ADDITIVE,
                        List.of("hour", "zone", "distance", "payment"),
                        BusinessMeaning.TRIPS, MetricUnit.COUNT, null, null, false),
                entry("share", "Share", AggregationType.RATIO, AdditiveScope.NON_ADDITIVE,
                        List.of("segment", "distance", "zone"),
                        BusinessMeaning.SHARE, MetricUnit.PERCENT, null, "total", true)
        );
    }

    private Map.Entry<String, MetricSemanticDefinition> entry(
            String key, String label, AggregationType agg, AdditiveScope scope,
            List<String> groupings, BusinessMeaning meaning, MetricUnit unit,
            String num, String denom, boolean requiresDenom
    ) {
        return Map.entry(key, new MetricSemanticDefinition(
                key, label, agg, scope, groupings, meaning, unit, num, denom, requiresDenom));
    }

    private String normalize(String raw) {
        return raw.toLowerCase(Locale.ROOT)
                .replace(" ", "_")
                .replace("-", "_");
    }

}
