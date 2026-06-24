package com.example.BACKEND.experiment.phase1;

public record Phase1CatalogueEntry(
        String columnName,
        String label,
        String role,
        String dataType,
        String defaultAggregation,
        String description
) {
    public Phase1CatalogueEntry(
            String columnName, String label, String role, String dataType, String defaultAggregation
    ) {
        this(columnName, label, role, dataType, defaultAggregation, null);
    }
}
