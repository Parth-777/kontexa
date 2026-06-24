package com.example.BACKEND.catalogue.decision.semantic;

/**
 * Ratio/composition plan: numerator share of denominator.
 * Example: SUM(tip_amount) / SUM(total_amount)
 */
public record ContributionAnalysisPlan(
        String numeratorMetric,
        String numeratorLabel,
        String denominatorMetric,
        String denominatorLabel,
        String shareFormula,
        boolean compositionBreakdown
) {
    public static ContributionAnalysisPlan of(
            String numerator, String numeratorLabel,
            String denominator, String denominatorLabel
    ) {
        return new ContributionAnalysisPlan(
                numerator, numeratorLabel,
                denominator, denominatorLabel,
                String.format("SUM(%s) / SUM(%s)", numerator, denominator),
                true
        );
    }
}
