package com.example.BACKEND.catalogue.semantic.canonical;

import com.example.BACKEND.catalogue.decision.planning.AnalysisIntent;
import com.example.BACKEND.catalogue.decision.planning.AnalysisPlan;
import com.example.BACKEND.catalogue.decision.planning.StructuredPlanProjection;
import com.example.BACKEND.catalogue.decision.semantics.catalog.SemanticDiscoveryDebug;

import java.util.List;
import java.util.Locale;

/**
 * Pure 1:1 {@link CanonicalQueryModel} → {@link AnalysisPlan} field mapper for downstream presentation.
 * Call only after {@link CanonicalQueryValidator} passes — no inference, defaults, or semantic repair.
 */
public final class CanonicalAnalysisPlanBridge {

    private CanonicalAnalysisPlanBridge() {}

    public static AnalysisPlan toAnalysisPlan(
            String question,
            String tableRef,
            CanonicalQueryModel model
    ) {
        if (model == null) {
            throw new IllegalArgumentException("canonical model required");
        }

        AnalysisIntent intent = copyIntent(model);
        StructuredPlanProjection projection = copyProjection(model);

        String measureColumn = model.measure() != null ? model.measure().column() : null;
        String partitionColumn = model.partition() != null ? model.partition().column() : null;
        String relationshipVariable = model.metadata() != null
                ? model.metadata().relationshipVariable() : null;
        String secondaryMetric = model.metadata() != null
                ? model.metadata().secondaryMetric() : null;

        return new AnalysisPlan(
                question,
                tableRef,
                intent,
                measureColumn,
                humanize(measureColumn),
                partitionColumn,
                humanize(partitionColumn),
                partitionColumn,
                relationshipVariable,
                humanize(relationshipVariable),
                secondaryMetric,
                humanize(secondaryMetric),
                true,
                List.of(),
                SemanticDiscoveryDebug.empty(null),
                List.of(),
                projection);
    }

    private static AnalysisIntent copyIntent(CanonicalQueryModel model) {
        String raw = model.metadata() != null ? model.metadata().intent() : null;
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("metadata.intent required");
        }
        String normalized = raw.toUpperCase(Locale.ROOT);
        String executionIntent = switch (normalized) {
            case "GROWTH" -> "TREND";
            case "PARETO" -> "RANKING";
            case "OUTLIER", "VARIANCE" -> "DISTRIBUTION";
            default -> normalized;
        };
        return AnalysisIntent.valueOf(executionIntent);
    }

    private static StructuredPlanProjection copyProjection(CanonicalQueryModel model) {
        List<String> dimensions = model.partition() != null && model.partition().column() != null
                ? List.of(model.partition().column())
                : List.of();
        String primaryAgg = model.measure() != null ? model.measure().aggregation() : null;
        String secondaryAgg = model.ratio() != null && model.ratio().denominator() != null
                ? model.ratio().denominator().aggregation()
                : null;
        String orderColumn = model.ordering() != null ? model.ordering().column() : null;
        String orderDirection = model.ordering() != null ? model.ordering().direction() : null;
        String timeGrain = model.partition() != null ? model.partition().timeGrain() : null;
        return new StructuredPlanProjection(
                dimensions,
                primaryAgg,
                secondaryAgg,
                orderColumn,
                orderDirection,
                model.limit(),
                timeGrain);
    }

    private static String humanize(String value) {
        return value == null ? null : value.replace('_', ' ');
    }
}
