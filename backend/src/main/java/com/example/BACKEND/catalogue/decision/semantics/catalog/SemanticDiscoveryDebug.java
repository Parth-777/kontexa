package com.example.BACKEND.catalogue.decision.semantics.catalog;

/**
 * Debug output for schema-driven semantic discovery.
 */
public record SemanticDiscoveryDebug(
        java.util.List<String> candidateMetrics,
        java.util.List<String> candidateDimensions,
        String metricResolution,
        String dimensionResolution,
        String intentResolution,
        double metricMatchScore,
        double dimensionMatchScore
) {
    public static SemanticDiscoveryDebug empty(SemanticCatalog catalog) {
        return new SemanticDiscoveryDebug(
                catalog != null ? catalog.candidateMetricKeys() : java.util.List.of(),
                catalog != null ? catalog.candidateDimensionKeys() : java.util.List.of(),
                "UNRESOLVED", "UNRESOLVED", "UNRESOLVED", 0, 0);
    }

    public java.util.Map<String, Object> toMap() {
        java.util.Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("candidate_metrics", candidateMetrics);
        m.put("candidate_dimensions", candidateDimensions);
        m.put("metric_resolution", metricResolution);
        m.put("dimension_resolution", dimensionResolution);
        m.put("intent_resolution", intentResolution);
        m.put("metric_match_score", metricMatchScore);
        m.put("dimension_match_score", dimensionMatchScore);
        return m;
    }
}
