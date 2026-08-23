package com.example.BACKEND.catalogue.investigation;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Deterministic post-binder gate: evidence kinds that require a grouping dimension
 * must carry a non-blank dimension before SQL compilation.
 *
 * <p>Does not invent dimensions. Relies on {@link InvestigationPlanMetricBinder}
 * having already propagated catalogue-bound top-level dimensions when available.
 */
final class EvidenceRequestDimensionContract {

    static final String MISSING_DIMENSION_REASON =
            "dimension required but no catalogue-bound dimension was planned";

    /**
     * Kinds whose SQL templates call {@code requireDimension}.
     * Correlation uses x/y params and is intentionally excluded.
     */
    static final Set<String> KINDS_REQUIRING_DIMENSION = Set.of(
            "metric_by_dimension",
            "distribution",
            "rank",
            "top_n",
            "bottom_n",
            "share",
            "concentration"
    );

    private EvidenceRequestDimensionContract() {
    }

    static boolean requiresDimension(String kind) {
        if (kind == null || kind.isBlank()) {
            return false;
        }
        return KINDS_REQUIRING_DIMENSION.contains(kind.trim().toLowerCase(Locale.ROOT));
    }

    static boolean hasDimension(InvestigationPlan.EvidenceRequest request) {
        return request != null
                && request.dimension() != null
                && !request.dimension().isBlank();
    }

    static Evaluation evaluate(InvestigationPlan plan) {
        List<InvestigationPlan.EvidenceRequest> executable = new ArrayList<>();
        List<Rejected> rejected = new ArrayList<>();
        if (plan == null || plan.evidenceRequests() == null) {
            return new Evaluation(List.of(), List.of());
        }
        for (InvestigationPlan.EvidenceRequest request : plan.evidenceRequests()) {
            if (request == null) {
                continue;
            }
            if (requiresDimension(request.kind()) && !hasDimension(request)) {
                rejected.add(new Rejected(request, MISSING_DIMENSION_REASON));
            } else {
                executable.add(request);
            }
        }
        return new Evaluation(List.copyOf(executable), List.copyOf(rejected));
    }

    /**
     * Plan containing only requests that may proceed to SQL compilation.
     */
    static InvestigationPlan planForCompilation(InvestigationPlan plan, Evaluation evaluation) {
        if (plan == null) {
            return null;
        }
        return new InvestigationPlan(
                plan.phenomenon(),
                plan.scopeFilters(),
                plan.hypotheses(),
                evaluation.executableRequests(),
                plan.insufficiency(),
                plan.semanticConfidence());
    }

    record Rejected(InvestigationPlan.EvidenceRequest request, String reason) {}

    record Evaluation(
            List<InvestigationPlan.EvidenceRequest> executableRequests,
            List<Rejected> rejected
    ) {
        boolean hasExecutable() {
            return executableRequests != null && !executableRequests.isEmpty();
        }

        boolean hasRejectedMissingDimension() {
            return rejected != null && !rejected.isEmpty();
        }

        /**
         * True when every planned request was rejected for missing dimension
         * (no other executable kinds remain).
         */
        boolean allRequestsRejectedForMissingDimension(InvestigationPlan plan) {
            if (plan == null || plan.evidenceRequests() == null || plan.evidenceRequests().isEmpty()) {
                return false;
            }
            if (hasExecutable()) {
                return false;
            }
            return hasRejectedMissingDimension();
        }

        List<Map<String, Object>> toDiagnostics() {
            List<Map<String, Object>> out = new ArrayList<>();
            if (rejected == null) {
                return out;
            }
            for (Rejected r : rejected) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("request_id", r.request().requestId());
                row.put("kind", r.request().kind());
                row.put("code", "MISSING_REQUIRED_DIMENSION");
                row.put("message", r.reason());
                out.add(row);
            }
            return out;
        }
    }
}
