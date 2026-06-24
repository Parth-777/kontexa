package com.example.BACKEND.catalogue.decision.presentation.executive;

/**
 * Business-readable metric tile for executive presentation.
 */
public record ExecutiveSupportingMetric(
        String label,
        String value,
        String unit,
        String context
) {}
