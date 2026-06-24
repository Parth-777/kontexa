package com.example.BACKEND.catalogue.decision.governance;

/**
 * Explicit denominator metadata — share of what, compared against what, over what scope.
 */
public record DenominatorContext(
        String  shareOf,
        String  comparedAgainst,
        String  timeRange,
        String  population,
        double  denominatorValue,
        int     populationCount,
        boolean globalPopulation
) {
    public static DenominatorContext unknown() {
        return new DenominatorContext("unknown", "unknown", "all", "all", 0, 0, false);
    }

    public static DenominatorContext forContribution(
            String metricLabel, String dimensionLabel, double totalValue, int groupCount, int rowCount
    ) {
        return new DenominatorContext(
                "total " + metricLabel + " across all " + dimensionLabel + " buckets",
                "equal-share baseline across " + groupCount + " groups",
                "full dataset",
                groupCount + " " + dimensionLabel + " groups (" + rowCount + " rows)",
                totalValue,
                rowCount,
                true
        );
    }

    public static DenominatorContext forRatio(String numeratorLabel, String denominatorLabel) {
        return new DenominatorContext(
                "share of " + denominatorLabel,
                "total " + denominatorLabel,
                "full dataset",
                numeratorLabel + " vs " + denominatorLabel,
                0, 0, true
        );
    }
}
