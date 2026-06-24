package com.example.BACKEND.catalogue.decision.verification;

/**
 * Transparent confidence factors — SQL and validation are source of truth, not LLM.
 */
public record ConfidenceDecomposition(
        double sqlValidity,
        double aggregationConsistency,
        double statisticalSeparation,
        double rowCoverage,
        double narrativeCertainty,
        double composite
) {
    public static ConfidenceDecomposition from(
            AnalyticalVerificationEngine.VerificationReport report,
            int sqlSucceeded,
            int sqlTotal,
            boolean narrativeGuardPassed
    ) {
        double sql = sqlTotal > 0 ? (double) sqlSucceeded / sqlTotal : 0;
        double agg = report != null && report.passed()
                ? Math.max(0, 1.0 - report.reconcileDeltaPct() / 100.0) : 0.2;
        double separation = report != null
                ? Math.min(1.0, report.coefficientOfVariation() / 0.5) : 0;
        double coverage = report != null && report.groupCount() >= 2
                ? Math.min(1.0, report.groupCount() / 8.0) : 0;
        double narrative = narrativeGuardPassed ? 0.85 : 0.5;

        double composite = sql * 0.25 + agg * 0.30 + separation * 0.20
                + coverage * 0.15 + narrative * 0.10;

        return new ConfidenceDecomposition(
                round(sql), round(agg), round(separation), round(coverage),
                round(narrative), round(composite));
    }

    private static double round(double v) {
        return Math.round(v * 1000.0) / 1000.0;
    }
}
