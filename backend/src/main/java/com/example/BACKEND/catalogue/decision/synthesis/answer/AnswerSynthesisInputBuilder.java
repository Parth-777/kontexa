package com.example.BACKEND.catalogue.decision.synthesis.answer;

import com.example.BACKEND.catalogue.decision.contracts.DecisionModels.ComputationResultSet;
import com.example.BACKEND.catalogue.decision.contracts.DecisionModels.QueryResult;
import com.example.BACKEND.catalogue.decision.contracts.DecisionModels.QuerySpec;
import com.example.BACKEND.catalogue.decision.execution.materialization.MaterializedQueryResult;
import com.example.BACKEND.catalogue.decision.planning.InvestigationPlan;
import com.example.BACKEND.catalogue.decision.semantics.MetricResolution;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Builds {@link AnswerSynthesisInput} from runtime execution artifacts.
 */
@Component
public class AnswerSynthesisInputBuilder {

    private final AnswerSynthesisProperties properties;

    public AnswerSynthesisInputBuilder(AnswerSynthesisProperties properties) {
        this.properties = properties;
    }

    public AnswerSynthesisInput build(
            String question,
            List<QuerySpec> specs,
            ComputationResultSet results,
            MetricResolution resolution,
            InvestigationPlan plan,
            double confidence,
            MaterializedQueryResult materialized,
            UUID runId
    ) {
        return build(question, specs, results, resolution, plan, confidence, materialized, runId, null, null, null);
    }

    public AnswerSynthesisInput build(
            String question,
            List<QuerySpec> specs,
            ComputationResultSet results,
            MetricResolution resolution,
            InvestigationPlan plan,
            double confidence,
            MaterializedQueryResult materialized,
            UUID runId,
            com.example.BACKEND.catalogue.semantic.canonical.CanonicalQueryModel canonical
    ) {
        return build(question, specs, results, resolution, plan, confidence, materialized, runId, canonical, null, null);
    }

    public AnswerSynthesisInput build(
            String question,
            List<QuerySpec> specs,
            ComputationResultSet results,
            MetricResolution resolution,
            InvestigationPlan plan,
            double confidence,
            MaterializedQueryResult materialized,
            UUID runId,
            com.example.BACKEND.catalogue.semantic.canonical.CanonicalQueryModel canonical,
            com.example.BACKEND.catalogue.decision.presentation.executive.ExecutivePresentation presentation
    ) {
        return build(question, specs, results, resolution, plan, confidence, materialized, runId, canonical, presentation, null);
    }

    public AnswerSynthesisInput build(
            String question,
            List<QuerySpec> specs,
            ComputationResultSet results,
            MetricResolution resolution,
            InvestigationPlan plan,
            double confidence,
            MaterializedQueryResult materialized,
            UUID runId,
            com.example.BACKEND.catalogue.semantic.canonical.CanonicalQueryModel canonical,
            com.example.BACKEND.catalogue.decision.presentation.executive.ExecutivePresentation presentation,
            com.example.BACKEND.catalogue.semantic.canonical.FormattedExecutiveTable executiveTable
    ) {
        List<Map<String, Object>> rows = extractPrimaryRows(specs, results);
        int cap = properties.getMaxRowsInPrompt();
        if (rows.size() > cap) {
            rows = List.copyOf(rows.subList(0, cap));
        }

        String sql = specs != null && !specs.isEmpty() && specs.getFirst().sql() != null
                ? specs.getFirst().sql() : "";

        AnswerSynthesisInput.MetricMetadata metric = metricMeta(resolution, plan, canonical);
        AnswerSynthesisInput.DimensionMetadata dimension = dimensionMeta(resolution, plan, canonical);

        String matType = materialized != null && materialized.resultType() != null
                ? materialized.resultType().name() : "NONE";

        int rowCount = countWarehouseRows(specs, results);

        return new AnswerSynthesisInput(
                question,
                sql,
                rows,
                metric,
                dimension,
                confidence,
                new AnswerSynthesisInput.ExecutionMetadata(
                        runId != null ? runId.toString() : "",
                        rowCount,
                        matType,
                        rowCount > 0),
                canonical,
                presentation,
                executiveTable);
    }

    private static AnswerSynthesisInput.MetricMetadata metricMeta(
            MetricResolution resolution,
            InvestigationPlan plan,
            com.example.BACKEND.catalogue.semantic.canonical.CanonicalQueryModel canonical
    ) {
        if (canonical != null) {
            if (canonical.measure() != null
                    && canonical.measure().column() != null && !canonical.measure().column().isBlank()) {
                return new AnswerSynthesisInput.MetricMetadata(
                        canonical.measure().column(),
                        humanize(canonical.measure().column()),
                        canonical.measure().aggregation() != null ? canonical.measure().aggregation() : "SUM");
            }
            return new AnswerSynthesisInput.MetricMetadata("", "", "SUM");
        }
        if (resolution != null && resolution.primaryMetric() != null) {
            return new AnswerSynthesisInput.MetricMetadata(
                    resolution.primaryMetric(),
                    resolution.primaryMetricLabel(),
                    "SUM");
        }
        if (plan != null && plan.reasoningPlan() != null
                && plan.reasoningPlan().metricBinding() != null) {
            var b = plan.reasoningPlan().metricBinding();
            return new AnswerSynthesisInput.MetricMetadata(
                    b.metricColumn(), b.metricLabel(),
                    b.aggregation() != null ? b.aggregation().name() : "SUM");
        }
        return new AnswerSynthesisInput.MetricMetadata("", "metric", "SUM");
    }

    private static AnswerSynthesisInput.DimensionMetadata dimensionMeta(
            MetricResolution resolution,
            InvestigationPlan plan,
            com.example.BACKEND.catalogue.semantic.canonical.CanonicalQueryModel canonical
    ) {
        if (canonical != null) {
            if (canonical.partition() != null
                    && canonical.partition().column() != null && !canonical.partition().column().isBlank()) {
                String col = canonical.partition().column();
                return new AnswerSynthesisInput.DimensionMetadata(col, humanize(col));
            }
            return new AnswerSynthesisInput.DimensionMetadata("", "");
        }
        if (resolution != null) {
            String col = resolution.grouping() != null ? resolution.grouping() : resolution.dimension();
            String label = resolution.dimensionLabel() != null
                    ? resolution.dimensionLabel() : col;
            if (col != null && !col.isBlank()) {
                return new AnswerSynthesisInput.DimensionMetadata(col, label);
            }
        }
        if (plan != null && plan.reasoningPlan() != null
                && plan.reasoningPlan().metricBinding() != null) {
            var b = plan.reasoningPlan().metricBinding();
            return new AnswerSynthesisInput.DimensionMetadata(
                    b.groupingColumn(), b.groupingLabel());
        }
        return new AnswerSynthesisInput.DimensionMetadata("", "");
    }

    public static List<Map<String, Object>> extractCanonicalRows(
            List<QuerySpec> specs,
            List<QueryResult> results
    ) {
        if (results == null || results.isEmpty()) {
            return List.of();
        }
        if (specs == null || specs.isEmpty()) {
            return List.of();
        }

        java.util.Set<String> keys = specs.stream()
                .map(QuerySpec::key)
                .collect(Collectors.toSet());

        List<Map<String, Object>> rows = new ArrayList<>();
        for (QueryResult qr : results) {
            if (qr.rows() == null || qr.rows().isEmpty()) {
                continue;
            }
            if (qr.key() != null && keys.contains(qr.key())) {
                for (Map<String, Object> row : qr.rows()) {
                    rows.add(new LinkedHashMap<>(row));
                }
            }
        }
        return rows;
    }

    private static List<Map<String, Object>> extractPrimaryRows(
            List<QuerySpec> specs, ComputationResultSet results
    ) {
        if (results == null || results.results() == null) {
            return List.of();
        }
        return extractCanonicalRows(specs, results.results());
    }

    private static String humanize(String col) {
        return col != null ? col.replace('_', ' ') : "";
    }

    private static int countWarehouseRows(List<QuerySpec> specs, ComputationResultSet results) {
        return extractPrimaryRows(specs, results).size();
    }
}
