package com.example.BACKEND.catalogue.investigation;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;

/**
 * Phase 2 investigation contract: evidence program without question categories.
 *
 * <p>{@code evidenceRequests} are the sole executable surface for
 * {@link InvestigationRuntime}. {@code hypotheses}, {@code insufficiency},
 * {@code EvidenceRequest.hypothesisId}, and {@code EvidenceRequest.evidencePurpose}
 * are planner metadata for narrative fidelity — never used to invent SQL.
 */
public record InvestigationPlan(
        Phenomenon phenomenon,
        List<InvestigationObjective.Filter> scopeFilters,
        List<Hypothesis> hypotheses,
        List<EvidenceRequest> evidenceRequests,
        Insufficiency insufficiency,
        double semanticConfidence
) {
    /** Backward-compatible constructor for plans without reasoning metadata. */
    public InvestigationPlan(
            Phenomenon phenomenon,
            List<InvestigationObjective.Filter> scopeFilters,
            List<EvidenceRequest> evidenceRequests,
            double semanticConfidence
    ) {
        this(phenomenon, scopeFilters, List.of(), evidenceRequests, null, semanticConfidence);
    }

    public InvestigationPlan {
        hypotheses = hypotheses == null ? List.of() : List.copyOf(hypotheses);
        scopeFilters = scopeFilters == null ? List.of() : List.copyOf(scopeFilters);
        evidenceRequests = evidenceRequests == null ? List.of() : List.copyOf(evidenceRequests);
    }

    public record Phenomenon(
            String subject,
            InvestigationObjective.Measure measure
    ) {}

    /**
     * Competing explanatory claim authored by the GPT planner.
     *
     * @param status PRIMARY | ALTERNATIVE | REJECTED_CANDIDATE
     */
    public record Hypothesis(
            String id,
            String claim,
            String status
    ) {
        public static final String PRIMARY = "PRIMARY";
        public static final String ALTERNATIVE = "ALTERNATIVE";
        public static final String REJECTED_CANDIDATE = "REJECTED_CANDIDATE";
    }

    /**
     * Planner honesty about catalogue limits — not runtime validation.
     */
    public record Insufficiency(
            boolean answerable,
            List<String> unknowns,
            List<String> blockedReasons
    ) {
        public Insufficiency {
            unknowns = unknowns == null ? List.of() : List.copyOf(unknowns);
            blockedReasons = blockedReasons == null ? List.of() : List.copyOf(blockedReasons);
        }

        public static Insufficiency answerableEmpty() {
            return new Insufficiency(true, List.of(), List.of());
        }
    }

    public record EvidenceRequest(
            String requestId,
            String kind,
            String role,
            InvestigationObjective.Measure measure,
            String dimension,
            InvestigationObjective.Slice slice,
            InvestigationObjective.Slice focalSlice,
            InvestigationObjective.Slice baselineSlice,
            JsonNode params,
            String hypothesisId,
            String evidencePurpose
    ) {
        public static final String PURPOSE_PRIMARY = "PRIMARY";
        public static final String PURPOSE_VALIDATION = "VALIDATION";
        public static final String PURPOSE_COMPARISON = "COMPARISON";
        public static final String PURPOSE_CONTRADICTION = "CONTRADICTION";
        public static final String PURPOSE_CONTEXT = "CONTEXT";

        /** Backward-compatible constructor without reasoning metadata. */
        public EvidenceRequest(
                String requestId,
                String kind,
                String role,
                InvestigationObjective.Measure measure,
                String dimension,
                InvestigationObjective.Slice slice,
                InvestigationObjective.Slice focalSlice,
                InvestigationObjective.Slice baselineSlice,
                JsonNode params
        ) {
            this(requestId, kind, role, measure, dimension, slice, focalSlice, baselineSlice,
                    params, null, null);
        }
    }
}
