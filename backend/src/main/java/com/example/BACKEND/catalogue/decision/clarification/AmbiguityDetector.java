package com.example.BACKEND.catalogue.decision.clarification;

import com.example.BACKEND.catalogue.decision.clarification.DomainOntology.OntologyMapping;
import com.example.BACKEND.catalogue.decision.planning.AnalyticalIntentType;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Detects multiple valid metric interpretations for vague questions.
 */
@Component
public class AmbiguityDetector {

    public record InterpretationCandidate(
            String metricKey,
            String metricLabel,
            com.example.BACKEND.catalogue.decision.governance.MetricSemanticTypes.AggregationType aggregation,
            AnalyticalIntentType intent,
            double score,
            String reason
    ) {}

    private final DomainOntology ontology;

    public AmbiguityDetector(DomainOntology ontology) {
        this.ontology = ontology;
    }

    public List<InterpretationCandidate> detect(String question, AnalyticalIntentType classifiedIntent) {
        String q = question.toLowerCase(Locale.ROOT);
        List<InterpretationCandidate> candidates = new ArrayList<>();

        if (q.contains("efficien") || q.contains("per mile") || q.contains("yield")) {
            candidates.add(candidate("revenue_per_mile", "Revenue per mile",
                    com.example.BACKEND.catalogue.decision.governance.MetricSemanticTypes.AggregationType.RATIO,
                    AnalyticalIntentType.EFFICIENCY, 0.9, "Efficiency language detected"));
        }
        if (q.contains("pricing") || q.contains("fare") && !q.contains("total")) {
            candidates.add(candidate("fare_amount", "Fare",
                    com.example.BACKEND.catalogue.decision.governance.MetricSemanticTypes.AggregationType.SUM,
                    AnalyticalIntentType.DISTRIBUTION, 0.85, "Pricing/fare language"));
        }
        if (q.contains("share") || q.contains("composition") || q.contains("mix")) {
            candidates.add(candidate("total_amount", "Revenue share",
                    com.example.BACKEND.catalogue.decision.governance.MetricSemanticTypes.AggregationType.SUM,
                    AnalyticalIntentType.COMPOSITION, 0.88, "Share/composition language"));
        }
        if (q.contains("volume") || q.contains("trip count") || q.contains("how many trips")) {
            candidates.add(candidate("volume", "Trip count",
                    com.example.BACKEND.catalogue.decision.governance.MetricSemanticTypes.AggregationType.COUNT,
                    AnalyticalIntentType.DISTRIBUTION, 0.82, "Volume language"));
        }
        if (q.contains("per trip")) {
            candidates.add(candidate("revenue_per_trip", "Revenue per trip",
                    com.example.BACKEND.catalogue.decision.governance.MetricSemanticTypes.AggregationType.RATIO,
                    AnalyticalIntentType.EFFICIENCY, 0.87, "Per-trip efficiency"));
        }

        candidates.add(candidate("total_amount", "Revenue",
                com.example.BACKEND.catalogue.decision.governance.MetricSemanticTypes.AggregationType.SUM,
                AnalyticalIntentType.CONTRIBUTION, scoreContribution(q), "Default revenue contribution"));

        if (ontology.isAmbiguousPhrase(question)) {
            candidates.add(candidate("total_amount", "Total revenue",
                    com.example.BACKEND.catalogue.decision.governance.MetricSemanticTypes.AggregationType.SUM,
                    AnalyticalIntentType.CONTRIBUTION, 0.75, "Ambiguous revenue impact phrasing"));
            candidates.add(candidate("revenue_per_mile", "Revenue per mile",
                    com.example.BACKEND.catalogue.decision.governance.MetricSemanticTypes.AggregationType.RATIO,
                    AnalyticalIntentType.EFFICIENCY, 0.7, "Alternative: efficiency view"));
            candidates.add(candidate("fare_amount", "Fare distribution",
                    com.example.BACKEND.catalogue.decision.governance.MetricSemanticTypes.AggregationType.SUM,
                    AnalyticalIntentType.DISTRIBUTION, 0.68, "Alternative: pricing view"));
        }

        if (classifiedIntent == AnalyticalIntentType.TREND_ANALYSIS) {
            candidates.add(candidate("total_amount", "Revenue",
                    com.example.BACKEND.catalogue.decision.governance.MetricSemanticTypes.AggregationType.SUM,
                    AnalyticalIntentType.TREND_ANALYSIS, 0.92, "Trend intent"));
        }

        return candidates.stream()
                .sorted(Comparator.comparingDouble(InterpretationCandidate::score).reversed())
                .distinct()
                .limit(5)
                .toList();
    }

    public boolean isAmbiguous(List<InterpretationCandidate> candidates) {
        if (candidates.size() < 2) return false;
        double top = candidates.getFirst().score();
        long close = candidates.stream().filter(c -> top - c.score() < 0.12).count();
        return close >= 2;
    }

    private double scoreContribution(String q) {
        double s = 0.8;
        if (q.contains("contribute") || q.contains("contribution")) s += 0.08;
        if (q.contains("distance") || q.contains("mile")) s += 0.05;
        if (q.contains("affect") || q.contains("impact")) s -= 0.05;
        return Math.min(0.95, s);
    }

    private InterpretationCandidate candidate(
            String key, String label,
            com.example.BACKEND.catalogue.decision.governance.MetricSemanticTypes.AggregationType agg,
            AnalyticalIntentType intent, double score, String reason
    ) {
        return new InterpretationCandidate(key, label, agg, intent, score, reason);
    }
}
