package com.example.BACKEND.catalogue.investigation;

import com.example.BACKEND.catalogue.semantic.phase2.StructuredSemanticPlan;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Propagates catalogue-bound top-level metric and dimensions from
 * {@link StructuredSemanticPlan} into {@link InvestigationPlan}.
 *
 * <p>Investigation plans are parsed from raw GPT JSON without column binding.
 * Top-level {@code metric} / {@code dimensions} are already catalogue-bound by the
 * structured planner. This binder reconciles those surfaces into evidence requests.
 *
 * <p>Addresses the measure-propagation defect only: when GPT leaves
 * {@code evidence_requests[*].measure} null while top-level {@code metric} is bound,
 * construction fills the missing measure. Does not resolve unbound synonyms
 * (e.g. {@code sessions} → {@code visitId}); that requires catalogue metric binding
 * before this stage.
 */
final class InvestigationPlanMetricBinder {

    private static final Set<String> KINDS_REQUIRING_DIMENSION = Set.of(
            "metric_by_dimension",
            "distribution",
            "correlation",
            "rank",
            "top_n",
            "bottom_n",
            "share",
            "concentration"
    );

    /** Kinds whose SQL templates call {@code requireMeasure}. */
    private static final Set<String> KINDS_REQUIRING_MEASURE = Set.of(
            "slice_metric",
            "metric_by_dimension",
            "rank",
            "top_n",
            "bottom_n",
            "share",
            "concentration",
            "time_series"
    );

    private InvestigationPlanMetricBinder() {
    }

    static InvestigationPlan bind(InvestigationPlan plan, StructuredSemanticPlan semanticPlan) {
        if (plan == null || semanticPlan == null) {
            return plan;
        }
        InvestigationPlan afterMetric = bindMetric(plan, semanticPlan);
        return bindDimension(afterMetric, semanticPlan);
    }

    private static InvestigationPlan bindMetric(InvestigationPlan plan, StructuredSemanticPlan semanticPlan) {
        InvestigationObjective.Measure bound = resolveBoundMeasure(semanticPlan);
        if (bound == null) {
            return plan;
        }

        InvestigationPlan.Phenomenon phenomenon = plan.phenomenon();
        if (phenomenon != null && isMissingMeasure(phenomenon.measure())) {
            phenomenon = new InvestigationPlan.Phenomenon(
                    phenomenon.subject(), mergeMeasure(phenomenon.measure(), bound));
        }

        List<InvestigationPlan.EvidenceRequest> requests = plan.evidenceRequests();
        if (requests == null || requests.isEmpty()) {
            return copyPlan(plan, phenomenon, requests);
        }

        List<InvestigationPlan.EvidenceRequest> out = new ArrayList<>(requests.size());
        for (InvestigationPlan.EvidenceRequest request : requests) {
            if (request == null) {
                continue;
            }
            if (needsMeasureBinding(request, bound)) {
                out.add(withMeasure(request, mergeMeasure(request.measure(), bound)));
            } else {
                out.add(request);
            }
        }
        return copyPlan(plan, phenomenon, List.copyOf(out));
    }

    private static InvestigationPlan bindDimension(InvestigationPlan plan, StructuredSemanticPlan semanticPlan) {
        String boundDimension = resolveBoundDimension(semanticPlan);
        if (boundDimension == null) {
            return plan;
        }

        List<InvestigationPlan.EvidenceRequest> requests = plan.evidenceRequests();
        if (requests == null || requests.isEmpty()) {
            return plan;
        }

        List<InvestigationPlan.EvidenceRequest> out = new ArrayList<>(requests.size());
        for (InvestigationPlan.EvidenceRequest request : requests) {
            if (request == null) {
                continue;
            }
            if (needsDimensionBinding(request, boundDimension)) {
                out.add(withDimension(request, boundDimension));
            } else {
                out.add(request);
            }
        }
        return copyPlan(plan, plan.phenomenon(), List.copyOf(out));
    }

    private static InvestigationPlan copyPlan(
            InvestigationPlan plan,
            InvestigationPlan.Phenomenon phenomenon,
            List<InvestigationPlan.EvidenceRequest> requests
    ) {
        return new InvestigationPlan(
                phenomenon,
                plan.scopeFilters(),
                plan.hypotheses(),
                requests,
                plan.insufficiency(),
                plan.semanticConfidence());
    }

    private static InvestigationPlan.EvidenceRequest withMeasure(
            InvestigationPlan.EvidenceRequest request,
            InvestigationObjective.Measure measure
    ) {
        return new InvestigationPlan.EvidenceRequest(
                request.requestId(),
                request.kind(),
                request.role(),
                measure,
                request.dimension(),
                request.slice(),
                request.focalSlice(),
                request.baselineSlice(),
                request.params(),
                request.hypothesisId(),
                request.evidencePurpose());
    }

    private static InvestigationPlan.EvidenceRequest withDimension(
            InvestigationPlan.EvidenceRequest request,
            String dimension
    ) {
        return new InvestigationPlan.EvidenceRequest(
                request.requestId(),
                request.kind(),
                request.role(),
                request.measure(),
                dimension,
                request.slice(),
                request.focalSlice(),
                request.baselineSlice(),
                request.params(),
                request.hypothesisId(),
                request.evidencePurpose());
    }

    private static String resolveBoundDimension(StructuredSemanticPlan semanticPlan) {
        if (semanticPlan.dimensions() == null || semanticPlan.dimensions().isEmpty()) {
            return null;
        }
        for (String dimension : semanticPlan.dimensions()) {
            if (dimension != null && !dimension.isBlank()) {
                return dimension;
            }
        }
        return null;
    }

    private static boolean needsDimensionBinding(
            InvestigationPlan.EvidenceRequest request,
            String boundDimension
    ) {
        if (boundDimension == null || boundDimension.isBlank()) {
            return false;
        }
        if (!requiresGroupingDimension(request.kind())) {
            return false;
        }
        // Explicit investigation_plan dimension wins.
        return request.dimension() == null || request.dimension().isBlank();
    }

    private static boolean requiresGroupingDimension(String kind) {
        if (kind == null || kind.isBlank()) {
            return false;
        }
        return KINDS_REQUIRING_DIMENSION.contains(kind.trim().toLowerCase(Locale.ROOT));
    }

    private static boolean needsMeasureBinding(
            InvestigationPlan.EvidenceRequest request,
            InvestigationObjective.Measure bound
    ) {
        if (bound == null || bound.column() == null || bound.column().isBlank()) {
            return false;
        }
        if (!requiresMeasure(request.kind())) {
            // distribution / correlation may omit measure; do not invent one.
            return false;
        }
        // Explicit non-blank investigation_plan measure wins (multi-metric plans).
        return isMissingMeasure(request.measure());
    }

    private static boolean requiresMeasure(String kind) {
        if (kind == null || kind.isBlank()) {
            return false;
        }
        return KINDS_REQUIRING_MEASURE.contains(kind.trim().toLowerCase(Locale.ROOT));
    }

    private static boolean isMissingMeasure(InvestigationObjective.Measure existing) {
        return existing == null || existing.column() == null || existing.column().isBlank();
    }

    private static InvestigationObjective.Measure resolveBoundMeasure(StructuredSemanticPlan semanticPlan) {
        String column = semanticPlan.metric();
        if (column == null || column.isBlank()) {
            return null;
        }
        String aggregation = null;
        if (semanticPlan.aggregations() != null) {
            aggregation = semanticPlan.aggregations().primary();
        }
        if (aggregation == null || aggregation.isBlank()) {
            aggregation = InvestigationObjective.AGG_COUNT;
        }
        return new InvestigationObjective.Measure(
                column, aggregation.trim().toUpperCase(), List.of());
    }

    private static InvestigationObjective.Measure mergeMeasure(
            InvestigationObjective.Measure existing,
            InvestigationObjective.Measure bound
    ) {
        String aggregation = bound.aggregation();
        if ((aggregation == null || aggregation.isBlank())
                && existing != null
                && existing.aggregation() != null
                && !existing.aggregation().isBlank()) {
            aggregation = existing.aggregation();
        }
        List<String> grain = existing != null && existing.grain() != null
                ? existing.grain()
                : List.of();
        return new InvestigationObjective.Measure(bound.column(), aggregation, grain);
    }
}
