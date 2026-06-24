package com.example.BACKEND.catalogue.decision.synthesis.answer;

import com.example.BACKEND.catalogue.decision.presentation.executive.ExecutivePresentation;
import com.example.BACKEND.catalogue.semantic.canonical.CanonicalQueryModel;
import com.example.BACKEND.catalogue.semantic.canonical.FormattedExecutiveTable;

import java.util.List;
import java.util.Map;

/**
 * Payload for post-warehouse GPT answer synthesis (no catalogue, no re-planning).
 */
public record AnswerSynthesisInput(
        String question,
        String generatedSql,
        List<Map<String, Object>> warehouseRows,
        MetricMetadata metric,
        DimensionMetadata dimension,
        double confidence,
        ExecutionMetadata execution,
        CanonicalQueryModel canonicalQueryModel,
        ExecutivePresentation presentation,
        FormattedExecutiveTable executiveTable
) {
    public record MetricMetadata(
            String column,
            String label,
            String aggregation
    ) {}

    public record DimensionMetadata(
            String column,
            String label
    ) {}

    public record ExecutionMetadata(
            String runId,
            int warehouseRowCount,
            String materializedResultType,
            boolean warehouseSucceeded
    ) {}

    public boolean hasWarehouseRows() {
        return warehouseRows != null && !warehouseRows.isEmpty();
    }
}
