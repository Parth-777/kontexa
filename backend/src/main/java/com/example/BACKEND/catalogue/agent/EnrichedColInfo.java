package com.example.BACKEND.catalogue.agent;

/**
 * LLM-enriched semantic metadata for one column, extracted from the catalogue snapshot.
 * Populated by CatalogueSemanticEnricher at approval time.
 *
 * {@code displayUnit} + {@code scaleToCanonical}: machine-readable Insights display semantics.
 * {@code grainLabel}: conservative entity name for COUNT(*) language (e.g. sessions, rows).
 */
public record EnrichedColInfo(
        String aggregationMethod,  // SUM | COUNT | AVG | LAST_VALUE | NONE
        String comparisonPeriod,   // WoW | MoM | YoY | NONE
        String dateGranularity,    // daily | weekly | monthly | event | N/A
        String businessMeaning,    // plain-English description
        String maxValue,           // latest known data date for lookback filtering
        String displayUnit,        // currency | percent | count | number | ...
        Double scaleToCanonical,   // raw * scale → canonical business units
        String grainLabel          // sessions | rows | records | ...
) {
    /** Backward-compatible constructor for call sites that omit metric semantics. */
    public EnrichedColInfo(
            String aggregationMethod,
            String comparisonPeriod,
            String dateGranularity,
            String businessMeaning,
            String maxValue) {
        this(aggregationMethod, comparisonPeriod, dateGranularity, businessMeaning, maxValue,
                null, null, null);
    }
}
