package com.example.BACKEND.catalogue.decision.exploration;

import com.example.BACKEND.catalogue.decision.clarification.AmbiguityDetector;
import com.example.BACKEND.catalogue.decision.clarification.AmbiguityDetector.InterpretationCandidate;
import com.example.BACKEND.catalogue.decision.contracts.DecisionModels.RegistryResolutionBundle;
import com.example.BACKEND.catalogue.decision.governance.MetricSemanticTypes.AggregationType;
import com.example.BACKEND.catalogue.decision.contracts.DecisionModels.IntentResolution;
import com.example.BACKEND.catalogue.decision.planning.AnalyticalIntentClassifier;
import com.example.BACKEND.catalogue.decision.planning.AnalyticalIntentType;
import com.example.BACKEND.catalogue.decision.semantic.ContributionAnalysisPlan;
import com.example.BACKEND.catalogue.decision.semantic.DimensionImpactPlan;
import com.example.BACKEND.catalogue.decision.semantic.SemanticAnalysisPlan;
import com.example.BACKEND.catalogue.decision.semantic.SemanticAnalyticalParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Generates ranked interpretation candidates for every analytical question.
 */
@Service
public class MultiCandidateInterpretationEngine {

    private static final Logger log = LoggerFactory.getLogger(MultiCandidateInterpretationEngine.class);

    private final SemanticAnalyticalParser semanticParser;
    private final FallbackAnalyticalHeuristics heuristics;
    private final AmbiguityDetector ambiguityDetector;
    private final AnalyticalIntentClassifier intentClassifier;

    public MultiCandidateInterpretationEngine(
            SemanticAnalyticalParser semanticParser,
            FallbackAnalyticalHeuristics heuristics,
            AmbiguityDetector ambiguityDetector,
            AnalyticalIntentClassifier intentClassifier
    ) {
        this.semanticParser = semanticParser;
        this.heuristics = heuristics;
        this.ambiguityDetector = ambiguityDetector;
        this.intentClassifier = intentClassifier;
    }

    public record CandidateSet(
            List<InterpretationCandidatePlan> candidates,
            SemanticAnalysisPlan              primarySemantic,
            InterpretationCandidatePlan       selected
    ) {}

    public CandidateSet generate(IntentResolution intent, RegistryResolutionBundle bundle) {
        String question = intent.question();
        Map<String, InterpretationCandidatePlan> byLabel = new LinkedHashMap<>();

        SemanticAnalysisPlan semantic = semanticParser.parseExploratory(question, bundle);
        if (semantic.parsed()) {
            InterpretationCandidatePlan fromSemantic = fromSemanticPlan(semantic);
            byLabel.put(fromSemantic.label(), fromSemantic);
        }

        for (InterpretationCandidatePlan h : heuristics.generate(question)) {
            byLabel.putIfAbsent(h.label(), h);
        }

        AnalyticalIntentType classified = intentClassifier.classify(intent).canonical();

        for (InterpretationCandidate c : ambiguityDetector.detect(question, classified)) {
            InterpretationCandidatePlan p = fromAmbiguity(c, question);
            byLabel.putIfAbsent(p.label(), p);
        }

        List<InterpretationCandidatePlan> ranked = byLabel.values().stream()
                .sorted(Comparator.comparingDouble(InterpretationCandidatePlan::confidence).reversed())
                .limit(AnalyticalExplorationPolicy.MAX_CANDIDATES)
                .toList();

        InterpretationCandidatePlan selected = ranked.isEmpty()
                ? null : ranked.getFirst();

        log.info("[exploration] candidate interpretations for '{}':", question);
        for (int i = 0; i < ranked.size(); i++) {
            InterpretationCandidatePlan c = ranked.get(i);
            log.info("[exploration]   #{} {} conf={} metric={} grouping={} intent={} source={}",
                    i + 1, c.label(), String.format(Locale.ROOT, "%.2f", c.confidence()), c.primaryMetric(),
                    c.grouping().isBlank() ? "none" : c.grouping(),
                    c.intent(), c.source());
        }

        return new CandidateSet(ranked, semantic.parsed() ? semantic : null, selected);
    }

    private InterpretationCandidatePlan fromSemanticPlan(SemanticAnalysisPlan semantic) {
        if (semantic.contributionPlan() != null) {
            ContributionAnalysisPlan cp = semantic.contributionPlan();
            return new InterpretationCandidatePlan(
                    cp.numeratorLabel() + " share of " + cp.denominatorLabel(),
                    semantic.planSummary(),
                    cp.numeratorMetric(), cp.numeratorLabel(), cp.denominatorMetric(), "",
                    AggregationType.RATIO, AnalyticalIntentType.COMPOSITION,
                    semantic.confidence(), "semantic_parser");
        }
        if (semantic.dimensionImpactPlan() != null) {
            DimensionImpactPlan dip = semantic.dimensionImpactPlan();
            return new InterpretationCandidatePlan(
                    dip.metricLabel() + " by " + dip.dimensionLabel(),
                    semantic.planSummary(),
                    dip.metricColumn(), dip.metricLabel(), dip.dimensionColumn(), dip.bucketStrategy(),
                    AggregationType.SUM, AnalyticalIntentType.CONTRIBUTION,
                    semantic.confidence(), "semantic_parser");
        }
        return new InterpretationCandidatePlan(
                semantic.primaryMetricLabel() + " analysis",
                semantic.planSummary(),
                semantic.primaryMetric(), semantic.primaryMetricLabel(),
                semantic.secondaryMetric(),
                semantic.groupingDimension() != null ? semantic.groupingDimension() : "",
                AggregationType.SUM, semantic.intent(),
                semantic.confidence(), "semantic_parser");
    }

    private InterpretationCandidatePlan fromAmbiguity(InterpretationCandidate c, String question) {
        String grouping = inferGrouping(question, c);
        return new InterpretationCandidatePlan(
                c.metricLabel() + " — " + c.reason(),
                c.reason(),
                c.metricKey(), c.metricLabel(), null, grouping,
                c.aggregation(), c.intent(), c.score(), "ambiguity_detector");
    }

    private String inferGrouping(String question, InterpretationCandidate c) {
        String q = question.toLowerCase(Locale.ROOT);
        if (q.contains("distance") || q.contains("mile")) return "trip_distance_bucket";
        if (q.contains("weekend")) return "weekend_flag";
        if (q.contains("zone")) return "pickup_zone_bucket";
        if (q.contains("hour")) return "pickup_hour";
        if (c.intent() == AnalyticalIntentType.COMPOSITION) return "";
        return "";
    }
}
