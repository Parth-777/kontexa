package com.example.BACKEND.catalogue.decision.analytics.aggregation;

import com.example.BACKEND.catalogue.decision.execution.ExecutionFindings.ConstructedEntity;
import com.example.BACKEND.catalogue.decision.execution.ExecutionFindings.RankedEntity;
import com.example.BACKEND.catalogue.decision.execution.ExecutionFindings.StructuralFinding;

import java.util.List;

/**
 * Output contract of {@link AggregationIntelligenceEngine}.
 *
 * Contains pre-synthesised analytical evidence computed from grouped
 * aggregations and comparative ranking prior to LLM narrative generation.
 */
public record AggregationIntelligenceResult(
        List<ConstructedEntity> groupedEntities,
        List<RankedEntity> contributorRanking,
        List<RankedEntity> efficiencyRanking,
        List<StructuralFinding> findings
) {
    public static AggregationIntelligenceResult empty() {
        return new AggregationIntelligenceResult(List.of(), List.of(), List.of(), List.of());
    }
}

