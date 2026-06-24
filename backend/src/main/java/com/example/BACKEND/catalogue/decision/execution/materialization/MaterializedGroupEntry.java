package com.example.BACKEND.catalogue.decision.execution.materialization;

/**
 * One row in the output of a GROUP BY execution.
 *
 * This is the structured evidence element consumed by synthesis.
 * It is never a raw row — it is always a pre-computed, ranked, comparative result.
 *
 * Fields are intentionally generic; no business domain is assumed:
 *   dimensionName  — e.g. "hour_of_day", "zone_id", "segment"
 *   entityKey      — the distinct group value, e.g. "18:00-19:00", "Airport", "Tier-1"
 *   totalValue     — SUM of the primary value metric for this group
 *   volumeCount    — SUM of the volume/count metric for this group (or row count)
 *   sharePct       — this group's share of the overall total (0-100)
 *   efficiencyRatio— totalValue / volumeCount (value per unit of activity)
 *   rank           — 1-based rank by totalValue descending
 *   percentileRank — 0-100 percentile position within this grouping
 *   tier           — TOP_DECILE / TOP_QUARTILE / ABOVE_AVERAGE / etc.
 *   multiplierVsAvg— totalValue / group average across all entries in this spec
 */
public record MaterializedGroupEntry(
        String dimensionName,
        String entityKey,
        double totalValue,
        double volumeCount,
        double sharePct,
        double efficiencyRatio,
        int    rank,
        double percentileRank,
        String tier,
        double multiplierVsAvg
) {}
