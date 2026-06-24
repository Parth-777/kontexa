package com.example.BACKEND.catalogue.decision.exploration;

import com.example.BACKEND.catalogue.decision.exploration.SoftSemanticValidator.ValidationMode;

import java.util.List;
import java.util.Locale;

/**
 * Central policy for hybrid analytical execution — tiers, modes, and fallback narratives.
 */
public final class HybridExecutionPolicy {

    private HybridExecutionPolicy() {}

    public record HybridPlan(
            PlannerConfidenceTier   confidenceTier,
            AnalyticalExecutionMode executionMode,
            String                  explorationNote,
            boolean                 exploratoryMode
    ) {}

    public static HybridPlan resolve(
            double adjustedConfidence,
            ValidationMode validationMode,
            InterpretationCandidatePlan selected
    ) {
        PlannerConfidenceTier tier = PlannerConfidenceTier.fromScore(
                adjustedConfidence, validationMode,
                selected != null ? selected.source() : null);
        AnalyticalExecutionMode mode = tier.executionMode();
        String note = buildExplorationNote(tier, mode, selected);
        boolean exploratory = mode != AnalyticalExecutionMode.STRICT_SEMANTIC;
        return new HybridPlan(tier, mode, note, exploratory);
    }

    /**
     * When confidence is low, prefer the strongest heuristic candidate over semantic parser output.
     */
    public static InterpretationCandidatePlan selectForExecution(
            List<InterpretationCandidatePlan> candidates,
            InterpretationCandidatePlan selected,
            AnalyticalExecutionMode mode
    ) {
        if (mode != AnalyticalExecutionMode.EXPLORATORY_HEURISTIC || candidates == null) {
            return selected;
        }
        return candidates.stream()
                .filter(c -> isHeuristicSource(c.source()))
                .max((a, b) -> Double.compare(a.confidence(), b.confidence()))
                .or(() -> candidates.stream()
                        .filter(c -> c.grouping() != null && !c.grouping().isBlank())
                        .findFirst())
                .orElse(selected);
    }

    public static String narrativeForGrouping(String metricLabel, String dimensionLabel) {
        String dim = dimensionLabel != null
                ? dimensionLabel.toLowerCase(Locale.ROOT).replace('_', ' ')
                : "segments";
        return String.format(Locale.ROOT,
                "Based on grouped %s patterns across %s.",
                metricLabel != null ? metricLabel.toLowerCase(Locale.ROOT) : "revenue",
                dim);
    }

    private static String buildExplorationNote(
            PlannerConfidenceTier tier,
            AnalyticalExecutionMode mode,
            InterpretationCandidatePlan selected
    ) {
        return switch (mode) {
            case STRICT_SEMANTIC -> "";
            case HYBRID -> AnalyticalExplorationPolicy.HYBRID_INTERPRETATION_NOTE;
            case EXPLORATORY_HEURISTIC -> {
                String dim = selected != null && selected.grouping() != null
                        ? selected.grouping().replace('_', ' ')
                        : "available dimensions";
                yield String.format(Locale.ROOT, "%s %s",
                        AnalyticalExplorationPolicy.CLOSEST_MATCH_NOTE,
                        narrativeForGrouping(
                                selected != null ? selected.primaryMetricLabel() : "revenue",
                                dim));
            }
        };
    }

    private static boolean isHeuristicSource(String source) {
        if (source == null) return false;
        return source.contains("heuristic")
                || source.contains("fallback")
                || source.contains("default")
                || source.contains("dictionary");
    }
}
