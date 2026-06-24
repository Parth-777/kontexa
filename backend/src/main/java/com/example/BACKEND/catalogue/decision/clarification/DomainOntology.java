package com.example.BACKEND.catalogue.decision.clarification;

import com.example.BACKEND.catalogue.decision.governance.MetricSemanticTypes.AggregationType;
import com.example.BACKEND.catalogue.decision.governance.MetricSemanticTypes.BusinessMeaning;
import com.example.BACKEND.catalogue.decision.planning.AnalyticalIntentType;
import com.example.BACKEND.catalogue.decision.planning.DomainAnalyticalDefaults;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Domain ontology — maps vague business language to concrete metric semantics.
 */
@Component
public class DomainOntology {

    public record OntologyMapping(
            String            term,
            String            metricKey,
            String            metricLabel,
            AggregationType   aggregation,
            AnalyticalIntentType defaultIntent,
            BusinessMeaning   meaning
    ) {}

    private static final List<OntologyMapping> NYC_TAXI = List.of(
            map("revenue", "total_amount", "Revenue", AggregationType.SUM,
                    AnalyticalIntentType.CONTRIBUTION, BusinessMeaning.REVENUE),
            map("total revenue", "total_amount", "Revenue", AggregationType.SUM,
                    AnalyticalIntentType.CONTRIBUTION, BusinessMeaning.REVENUE),
            map("efficiency", "revenue_per_mile", "Revenue per mile", AggregationType.RATIO,
                    AnalyticalIntentType.EFFICIENCY, BusinessMeaning.EFFICIENCY),
            map("revenue per mile", "revenue_per_mile", "Revenue per mile", AggregationType.RATIO,
                    AnalyticalIntentType.EFFICIENCY, BusinessMeaning.EFFICIENCY),
            map("revenue per trip", "revenue_per_trip", "Revenue per trip", AggregationType.RATIO,
                    AnalyticalIntentType.EFFICIENCY, BusinessMeaning.EFFICIENCY),
            map("pricing", "fare_amount", "Fare", AggregationType.SUM,
                    AnalyticalIntentType.DISTRIBUTION, BusinessMeaning.REVENUE),
            map("fare", "fare_amount", "Fare", AggregationType.SUM,
                    AnalyticalIntentType.DISTRIBUTION, BusinessMeaning.REVENUE),
            map("volume", "volume", "Trip count", AggregationType.COUNT,
                    AnalyticalIntentType.DISTRIBUTION, BusinessMeaning.TRIPS),
            map("trips", "volume", "Trip count", AggregationType.COUNT,
                    AnalyticalIntentType.DISTRIBUTION, BusinessMeaning.TRIPS),
            map("trip count", "volume", "Trip count", AggregationType.COUNT,
                    AnalyticalIntentType.DISTRIBUTION, BusinessMeaning.TRIPS),
            map("share", "total_amount", "Revenue share", AggregationType.SUM,
                    AnalyticalIntentType.COMPOSITION, BusinessMeaning.SHARE),
            map("revenue share", "total_amount", "Revenue share", AggregationType.SUM,
                    AnalyticalIntentType.COMPOSITION, BusinessMeaning.SHARE),
            map("tip amount", "tip_amount", "Tips", AggregationType.SUM,
                    AnalyticalIntentType.COMPOSITION, BusinessMeaning.REVENUE),
            map("tips", "tip_amount", "Tips", AggregationType.SUM,
                    AnalyticalIntentType.COMPOSITION, BusinessMeaning.REVENUE),
            map("trip distance", "trip_distance", "Trip Distance", AggregationType.AVG,
                    AnalyticalIntentType.CONTRIBUTION, BusinessMeaning.DISTANCE)
    );

    private static final List<String> AMBIGUOUS_TERMS = List.of(
            "revenue model", "affect", "impact", "influence", "model",
            "drive", "relationship", "effect on"
    );

    private final DomainAnalyticalDefaults domainDefaults;

    public DomainOntology(DomainAnalyticalDefaults domainDefaults) {
        this.domainDefaults = domainDefaults;
    }

    public List<OntologyMapping> mappingsFor(String question) {
        if (isNycContext(question)) return NYC_TAXI;
        return NYC_TAXI;
    }

    public boolean isAmbiguousPhrase(String question) {
        String q = question.toLowerCase(Locale.ROOT);
        if (AMBIGUOUS_TERMS.stream().anyMatch(q::contains)) return true;
        return q.contains("revenue") && !q.contains("per mile") && !q.contains("per trip")
                && !q.contains("share") && (q.contains("model") || q.contains("affect")
                || q.contains("impact") || q.contains("how does"));
    }

    public String defaultGrouping(String question) {
        return resolveGrouping(question);
    }

    /**
     * Resolves grouping dimension — never falls back to generic "segment".
     */
    public String resolveGrouping(String question) {
        String q = question.toLowerCase(Locale.ROOT);
        if (q.contains("trip distance") || (q.contains("distance") && !q.contains("contribute"))) {
            return domainDefaults.resolve(question, null).distanceColumn() + "_bucket";
        }
        if (q.contains("mile") && !q.contains("per mile")) {
            return "trip_distance_bucket";
        }
        if (q.contains("hour") || q.contains("time of day")) return "pickup_hour";
        if (q.contains("zone") || q.contains("location") || q.contains("borough")) return "pickup_zone";
        if (q.contains("payment")) return "payment_type";
        if (q.contains("vendor")) return "vendor_id";
        if (q.contains("segment") || q.contains("breakdown") || q.contains("split")) return "segment";
        return null;
    }

    private boolean isNycContext(String question) {
        return "nyc_taxi".equals(domainDefaults.resolve(question, null).domainKey());
    }

    private static OntologyMapping map(
            String term, String key, String label, AggregationType agg,
            AnalyticalIntentType intent, BusinessMeaning meaning
    ) {
        return new OntologyMapping(term, key, label, agg, intent, meaning);
    }
}
