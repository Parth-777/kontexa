package com.example.BACKEND.catalogue.decision.planning;

import java.util.List;

/**
 * Assessment of whether assembled evidence is sufficient to support
 * the conclusions required by the {@link InvestigationPlan}.
 *
 * Produced by {@link EvidenceCoverageChecker} after evidence assembly.
 * Consumed by {@link com.example.BACKEND.catalogue.decision.calibration.ResponseCalibrationEngine}
 * and the synthesis prompt to calibrate confidence and scope.
 *
 * Fields:
 *   sufficientForSynthesis      — false: synthesis should not make strong claims
 *   coverageScore               — 0.0–1.0 fraction of required metrics covered
 *   coveredDimensions           — analytical dimensions with evidence
 *   missingDimensions           — required dimensions with no evidence
 *   confidenceAdjustment        — multiplier applied to evidence confidence (0.5–1.0)
 *   synthesisGuidanceNote       — instruction injected into the synthesis prompt
 */
public record EvidenceCoverageReport(
        boolean      sufficientForSynthesis,
        double       coverageScore,
        List<String> coveredDimensions,
        List<String> missingDimensions,
        double       confidenceAdjustment,
        String       synthesisGuidanceNote
) {
    public static EvidenceCoverageReport full() {
        return new EvidenceCoverageReport(true, 1.0,
                List.of(), List.of(), 1.0,
                "Evidence is comprehensive. Proceed with full synthesis confidence.");
    }

    public static EvidenceCoverageReport partial(double score, List<String> missing) {
        String note = missing.isEmpty()
                ? "Evidence is adequate. Minor analytical gaps present."
                : "Evidence is partial. Missing dimensions: " + String.join(", ", missing)
                  + ". Frame conclusions proportionally to available evidence.";
        return new EvidenceCoverageReport(score >= 0.5, score, List.of(), missing,
                Math.max(0.5, score), note);
    }

    public static EvidenceCoverageReport insufficient(List<String> missing) {
        return new EvidenceCoverageReport(false, 0.2, List.of(), missing, 0.4,
                "WARNING: Evidence coverage is insufficient for strong conclusions. "
                + "Acknowledge data limitations explicitly. "
                + "Do not fabricate findings for missing dimensions: " + String.join(", ", missing));
    }
}
