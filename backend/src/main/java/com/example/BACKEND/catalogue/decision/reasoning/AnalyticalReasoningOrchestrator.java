package com.example.BACKEND.catalogue.decision.reasoning;

import com.example.BACKEND.catalogue.charts.ChartSpec;
import com.example.BACKEND.catalogue.decision.analytics.AnalyticalDepthResult;
import com.example.BACKEND.catalogue.decision.findings.AnalyticalFinding;
import com.example.BACKEND.catalogue.decision.findings.StructuredFindingsBundle;
import com.example.BACKEND.catalogue.decision.planning.AnalyticalReasoningPlan;
import com.example.BACKEND.catalogue.decision.planning.InvestigationPlan;
import com.example.BACKEND.catalogue.decision.presentation.VisualizationPlanner;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * Orchestrates statistical interpretation, comparative reasoning, narrative intelligence,
 * insight prioritization, and visualization grounding into unified finding objects.
 */
@Service
public class AnalyticalReasoningOrchestrator {

    private final FindingComparativeEnricher comparativeEnricher;
    private final NarrativeIntelligenceEngine narrativeEngine;
    private final AnalyticalInsightPrioritizer prioritizer;
    private final VisualizationPlanner visualizationPlanner;

    public AnalyticalReasoningOrchestrator(
            FindingComparativeEnricher comparativeEnricher,
            NarrativeIntelligenceEngine narrativeEngine,
            AnalyticalInsightPrioritizer prioritizer,
            VisualizationPlanner visualizationPlanner
    ) {
        this.comparativeEnricher = comparativeEnricher;
        this.narrativeEngine = narrativeEngine;
        this.prioritizer = prioritizer;
        this.visualizationPlanner = visualizationPlanner;
    }

    public ReasoningResult enrich(
            StructuredFindingsBundle bundle,
            AnalyticalDepthResult depth,
            InvestigationPlan plan
    ) {
        if (bundle == null || !bundle.hasStructuredFindings()) {
            return ReasoningResult.empty();
        }

        AnalyticalReasoningPlan reasoningPlan = plan != null ? plan.reasoningPlan() : null;
        var intent = plan != null ? plan.intentType() : null;

        List<AnalyticalFinding> raw = bundle.allFindings();
        List<AnalyticalInsightPrioritizer.ScoredFinding> top =
                prioritizer.prioritize(raw, depth, intent);

        List<GroundedAnalyticalFinding> grounded = new ArrayList<>();
        for (var scored : top) {
            AnalyticalFinding f = scored.finding();
            StatisticalInterpretation s = scored.statistics();
            String comparative = comparativeEnricher.enrich(f, reasoningPlan);
            double trust = scored.priorityScore();
            String narrative = narrativeEngine.synthesize(f, s, comparative, intent, trust);
            String chartNote = narrativeEngine.chartExplanation(f, s, intent);
            ChartSpec chart = visualizationPlanner.planForFinding(f, depth);

            String displayNarrative = narrative.isBlank() ? chartNote : narrative;
            grounded.add(new GroundedAnalyticalFinding(
                    f, s, displayNarrative, comparative, chart, scored.priorityScore()));
        }

        ChartSpec primaryChart = grounded.isEmpty() ? null : grounded.getFirst().chartSpec();
        return new ReasoningResult(grounded, primaryChart);
    }

    public record ReasoningResult(
            List<GroundedAnalyticalFinding> prioritizedFindings,
            ChartSpec primaryChart
    ) {
        public static ReasoningResult empty() {
            return new ReasoningResult(List.of(), null);
        }

        public List<AnalyticalFinding> findingsOnly() {
            return prioritizedFindings.stream()
                    .map(GroundedAnalyticalFinding::finding)
                    .toList();
        }
    }
}
