package com.example.BACKEND.catalogue.agent;

/**
 * LLM-enriched semantic metadata for one column, extracted from the catalogue snapshot.
 * Populated by CatalogueSemanticEnricher at approval time.
 */
public record EnrichedColInfo(
        String aggregationMethod,  // SUM | COUNT | AVG | LAST_VALUE | NONE
        String comparisonPeriod,   // WoW | MoM | YoY | NONE
        String dateGranularity,    // daily | weekly | monthly | event | N/A
        String businessMeaning,    // plain-English description
        String maxValue            // latest known data date for lookback filtering
) {}
