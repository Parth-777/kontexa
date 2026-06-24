package com.example.BACKEND.catalogue.decision.planning;

import com.example.BACKEND.catalogue.decision.semantics.catalog.SemanticDiscoveryDebug;
import com.example.BACKEND.catalogue.decision.transforms.TransformationStep;

import java.util.List;

/**
 * Universal analytical plan — single contract consumed by SQL generation and presentation.
 *
 * All bindings (table, metrics, dimensions, intent) must originate from schema resolution.
 * No dataset-specific defaults belong in this record.
 */
public record AnalysisPlan(
        String question,
        String tableRef,
        AnalysisIntent intent,
        String primaryMetric,
        String primaryMetricLabel,
        String dimension,
        String dimensionLabel,
        String groupingAlias,
        String relationshipVariable,
        String relationshipVariableLabel,
        String secondaryMetric,
        String secondaryMetricLabel,
        boolean executable,
        List<String> blockingReasons,
        SemanticDiscoveryDebug discovery,
        List<TransformationStep> transformations,
        StructuredPlanProjection structuredProjection
) {
    public static AnalysisPlan blocked(String question, String reason) {
        return new AnalysisPlan(
                question, null, AnalysisIntent.DISTRIBUTION,
                null, null, null, null, null,
                null, null, null, null,
                false, List.of(reason),
                SemanticDiscoveryDebug.empty(null),
                List.of(),
                StructuredPlanProjection.empty());
    }

    public String blockingReason() {
        return blockingReasons == null || blockingReasons.isEmpty()
                ? "Analysis plan not executable"
                : String.join("; ", blockingReasons);
    }
}
