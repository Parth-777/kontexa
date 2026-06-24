package com.example.BACKEND.experiment.phase1;

import java.util.List;

/**
 * Approved catalogue sent to GPT — column names and labels only (no aliases).
 */
public record Phase1CatalogueSnapshot(
        String tableRef,
        List<Phase1CatalogueEntry> entries,
        Phase1CataloguePayloadMode payloadMode
) {
    public Phase1CatalogueSnapshot(String tableRef, List<Phase1CatalogueEntry> entries) {
        this(tableRef, entries, Phase1CataloguePayloadMode.WITH_DESCRIPTIONS);
    }
    public List<String> metricColumns() {
        return entries.stream()
                .filter(e -> "metric".equals(e.role()))
                .map(Phase1CatalogueEntry::columnName)
                .toList();
    }

    public List<String> dimensionColumns() {
        return entries.stream()
                .filter(e -> "dimension".equals(e.role()))
                .map(Phase1CatalogueEntry::columnName)
                .toList();
    }

    public List<String> allColumns() {
        return entries.stream().map(Phase1CatalogueEntry::columnName).toList();
    }
}
