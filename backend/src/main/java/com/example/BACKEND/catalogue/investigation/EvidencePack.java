package com.example.BACKEND.catalogue.investigation;

import java.util.List;
import java.util.Map;

/**
 * Evidence-first investigation pack. {@code requestResults} is the primary payload.
 * Reasoning operates only over this pack — never over question categories.
 *
 * <p>Planner metadata ({@code hypotheses}, {@code insufficiency}, per-request
 * purpose / hypothesis links) is carried for narrative fidelity only.
 */
public record EvidencePack(
        String question,
        InvestigationPlan.Phenomenon phenomenon,
        List<InvestigationPlan.Hypothesis> hypotheses,
        InvestigationPlan.Insufficiency insufficiency,
        List<RequestResult> requestResults,
        Coverage coverage,
        Confidence confidence,
        String tableRef
) {
    /** Backward-compatible constructor without planner reasoning metadata. */
    public EvidencePack(
            String question,
            InvestigationPlan.Phenomenon phenomenon,
            List<RequestResult> requestResults,
            Coverage coverage,
            Confidence confidence,
            String tableRef
    ) {
        this(question, phenomenon, List.of(), null, requestResults, coverage, confidence, tableRef);
    }

    public EvidencePack {
        hypotheses = hypotheses == null ? List.of() : List.copyOf(hypotheses);
        requestResults = requestResults == null ? List.of() : List.copyOf(requestResults);
    }

    public record RequestResult(
            String requestId,
            String kind,
            String role,
            String hypothesisId,
            String evidencePurpose,
            List<String> queryIds,
            List<Map<String, Object>> rows,
            boolean ok,
            String error,
            String sql
    ) {
        public static RequestResult success(
                String requestId,
                String kind,
                String role,
                List<String> queryIds,
                List<Map<String, Object>> rows
        ) {
            return success(requestId, kind, role, null, null, queryIds, rows, null);
        }

        public static RequestResult success(
                String requestId,
                String kind,
                String role,
                List<String> queryIds,
                List<Map<String, Object>> rows,
                String sql
        ) {
            return success(requestId, kind, role, null, null, queryIds, rows, sql);
        }

        public static RequestResult success(
                String requestId,
                String kind,
                String role,
                String hypothesisId,
                String evidencePurpose,
                List<String> queryIds,
                List<Map<String, Object>> rows,
                String sql
        ) {
            return new RequestResult(
                    requestId, kind, role, hypothesisId, evidencePurpose,
                    queryIds == null ? List.of() : List.copyOf(queryIds),
                    rows == null ? List.of() : List.copyOf(rows),
                    true, null, sql);
        }

        public static RequestResult failure(
                String requestId,
                String kind,
                String role,
                String error
        ) {
            return failure(requestId, kind, role, null, null, error);
        }

        public static RequestResult failure(
                String requestId,
                String kind,
                String role,
                String hypothesisId,
                String evidencePurpose,
                String error
        ) {
            return new RequestResult(
                    requestId, kind, role, hypothesisId, evidencePurpose,
                    List.of(), List.of(), false, error, null);
        }
    }

    public record Coverage(
            int requestsPlanned,
            int requestsSucceeded,
            int requestsFailed
    ) {}

    public record Confidence(
            String level,
            List<String> reasons
    ) {
        public static final String LOW = "LOW";
        public static final String MODERATE = "MODERATE";
        public static final String HIGH = "HIGH";
    }
}
