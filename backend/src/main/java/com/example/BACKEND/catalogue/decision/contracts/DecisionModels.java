package com.example.BACKEND.catalogue.decision.contracts;

import com.example.BACKEND.catalogue.decision.planning.SemanticShadowComparison;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Canonical contracts for the Phase-1 decision runtime vertical slice.
 * All decision modules depend only on this package — no cross-module DTOs.
 */
public final class DecisionModels {

    private DecisionModels() {}

    // ─────────────────────────────────────────────────────
    // Enums
    // ─────────────────────────────────────────────────────

    public enum ExecutionState { QUEUED, RUNNING, COMPLETED, PARTIAL, FAILED, CANCELLED }

    public enum ExecutionStage {
        INTENT_RESOLUTION,
        REGISTRY_RESOLUTION,
        ANALYTICAL_PLANNING,          // decomposes objective into structured investigation plan
        SEMANTIC_ANALYTICAL_EXECUTION, // converts plan+schema into analytical SQL blueprints
        METRIC_PACK_PLANNING,
        WAREHOUSE_COMPUTE,
        ANALYTICAL_DEPTH_COMPUTATION, // deep structural analysis before evidence assembly
        DYNAMIC_ANALYTICAL_EXECUTION, // entity construction + specialized computation + ranking
        STRUCTURED_FINDINGS,          // converts materialized evidence into typed analytical findings
        EVIDENCE_ASSEMBLY,
        EVIDENCE_COVERAGE,            // validates evidence completeness against the investigation plan
        MATERIALITY_RANKING,
        REASONING_CONSTITUTION,   // epistemic review: labels claims before LLM synthesis
        RESPONSE_CALIBRATION,     // determines synthesis depth, mode, narrative density
        EXECUTIVE_SYNTHESIS,
        COMPLETED
    }

    // ─────────────────────────────────────────────────────
    // Epistemic classification
    // ─────────────────────────────────────────────────────

    /**
     * The four epistemic categories of analytical claims.
     * Claims are classified before reaching the LLM.
     */
    public enum EpistemicLabel {
        /** Directly computable from warehouse evidence. State as fact. */
        OBSERVATION,
        /** Reasonable conclusion derived from comparative evidence. State as conclusion. */
        ANALYTICAL_INFERENCE,
        /** Possible explanation requiring validation. Frame with "may", "warrants investigation". */
        HYPOTHESIS,
        /** References external unverifiable factors. FILTERED OUT — never sent to LLM. */
        UNSUPPORTED_SPECULATION
    }

    /**
     * A single claim with its epistemic label, confidence, and evidence traceability.
     */
    public record LabeledClaim(
            String         claimText,
            EpistemicLabel label,
            double         confidence,
            String         evidenceRef,
            String         reasoningBasis
    ) {}

    /**
     * Output of the {@link com.example.BACKEND.catalogue.decision.reasoning.ReasoningConstitutionEngine}.
     * Passed to synthesis so the LLM receives only validated, labeled claims.
     */
    public record ConstitutionReview(
            List<LabeledClaim> observations,
            List<LabeledClaim> analyticalInferences,
            List<LabeledClaim> hypotheses,
            List<LabeledClaim> filteredSpeculation,
            List<String>       reasoningConstraints,
            int                totalClaimsReviewed
    ) {}

    public enum ComparisonType {
        PERIOD_OVER_PERIOD,
        YEAR_OVER_YEAR,
        VS_BASELINE,
        VS_PEER,
        VS_TARGET,
        VS_COHORT_AVERAGE,
        VS_SEGMENT_NORM
    }

    // ─────────────────────────────────────────────────────
    // Registry / Planning
    // ─────────────────────────────────────────────────────

    public record IntentResolution(
            UUID   runId,
            String tenantId,
            String question,
            String objectiveKey,
            double confidence
    ) {}

    public record EntityDescriptor(
            String       key,
            String       tableRef,
            List<String> grainKeys,
            List<String> semanticTags
    ) {}

    public record MetricDescriptor(
            String key,
            String expressionTemplate,
            String valueType,
            String aggregation,
            String unit
    ) {}

    public record DimensionDescriptor(
            String key,
            String expression,
            String type
    ) {}

    public record ObjectiveDescriptor(
            String       key,
            String       analysisType,
            List<String> requiredSignals
    ) {}

    public record RegistryResolutionBundle(
            List<EntityDescriptor>    entities,
            List<MetricDescriptor>    metrics,
            List<DimensionDescriptor> dimensions,
            ObjectiveDescriptor       objective
    ) {}

    public record QuerySpec(
            String              key,
            String              sql,
            Map<String, Object> params
    ) {}

    public record MetricPackExecutionPlan(
            String          packKey,
            String          version,
            String          tenantId,
            List<QuerySpec> querySpecs
    ) {}

    public record QueryResult(
            String                    key,
            List<Map<String, Object>> rows,
            long                      elapsedMs
    ) {}

    public record ComputationResultSet(
            UUID                runId,
            List<QueryResult>   results,
            Map<String, Object> executionMeta
    ) {}

    // ─────────────────────────────────────────────────────
    // Comparative Intelligence
    // ─────────────────────────────────────────────────────

    /**
     * A single directional comparison for one metric on one entity.
     * Multiple ComparisonContexts are attached to each EvidenceObject.
     */
    public record ComparativeContext(
            String         entityRef,
            String         metricKey,
            double         currentValue,
            double         previousValue,
            double         delta,
            double         deltaPercent,
            ComparisonType comparisonType,
            String         currentPeriod,
            String         referencePeriod,
            String         directionLabel    // "up", "down", "flat"
    ) {}

    // ─────────────────────────────────────────────────────
    // Investigation Tree
    // ─────────────────────────────────────────────────────

    /**
     * One node in a hierarchical investigative drill-down.
     * The tree is built analytically from evidence — not via autonomous agents.
     */
    public record InvestigationNode(
            String               signal,
            String               dimension,
            String               segmentKey,
            String               segmentValue,
            double               impactScore,
            String               interpretation,
            List<InvestigationNode> children
    ) {}

    // ─────────────────────────────────────────────────────
    // Evidence
    // ─────────────────────────────────────────────────────

    public record EvidenceObject(
            String                    evidenceId,
            String                    entityRef,
            Map<String, Object>       metrics,
            Map<String, Object>       comparisons,
            Map<String, Object>       signals,
            List<ComparativeContext>  comparativeContexts,
            List<InvestigationNode>   investigationTree,
            double                    confidence,
            List<String>              lineageRefs
    ) {}

    public record RankedEvidence(
            String              evidenceId,
            double              score,
            int                 rank,
            Map<String, Double> featureVector,
            String              policyVersion
    ) {}

    // ─────────────────────────────────────────────────────
    // Executive Output
    // ─────────────────────────────────────────────────────

    public record InsightOutput(
            String       insightId,
            String       title,
            String       narrative,
            List<String> actions,
            List<String> evidenceRefs,
            List<String> strategicImplications,
            List<String> operationalRisks,
            List<String> businessCauses,
            String       prioritizationRationale
    ) {}

    /** Full decision run output — LLM insight + structured analytical presentation. */
    public record DecisionRunResult(
            InsightOutput insight,
            com.example.BACKEND.catalogue.decision.presentation.AnalyticalResponse analytical,
            com.example.BACKEND.catalogue.decision.verification.AnalyticalVerificationOrchestrator.VerificationContext verification,
            SemanticShadowComparison semanticShadow,
            com.example.BACKEND.catalogue.decision.synthesis.answer.AnswerSynthesisOutput answerSynthesis,
            com.example.BACKEND.catalogue.decision.presentation.executive.ExecutivePresentation executivePresentation,
            com.example.BACKEND.catalogue.semantic.canonical.FormattedExecutiveTable executiveTable
    ) {
        public DecisionRunResult(
                InsightOutput insight,
                com.example.BACKEND.catalogue.decision.presentation.AnalyticalResponse analytical
        ) {
            this(insight, analytical, null, null, null, null, null);
        }

        public DecisionRunResult(
                InsightOutput insight,
                com.example.BACKEND.catalogue.decision.presentation.AnalyticalResponse analytical,
                com.example.BACKEND.catalogue.decision.verification.AnalyticalVerificationOrchestrator.VerificationContext verification
        ) {
            this(insight, analytical, verification, null, null, null, null);
        }

        public DecisionRunResult(
                InsightOutput insight,
                com.example.BACKEND.catalogue.decision.presentation.AnalyticalResponse analytical,
                com.example.BACKEND.catalogue.decision.verification.AnalyticalVerificationOrchestrator.VerificationContext verification,
                SemanticShadowComparison semanticShadow
        ) {
            this(insight, analytical, verification, semanticShadow, null, null, null);
        }

        public DecisionRunResult(
                InsightOutput insight,
                com.example.BACKEND.catalogue.decision.presentation.AnalyticalResponse analytical,
                com.example.BACKEND.catalogue.decision.verification.AnalyticalVerificationOrchestrator.VerificationContext verification,
                SemanticShadowComparison semanticShadow,
                com.example.BACKEND.catalogue.decision.synthesis.answer.AnswerSynthesisOutput answerSynthesis
        ) {
            this(insight, analytical, verification, semanticShadow, answerSynthesis, null, null);
        }

        public DecisionRunResult(
                InsightOutput insight,
                com.example.BACKEND.catalogue.decision.presentation.AnalyticalResponse analytical,
                com.example.BACKEND.catalogue.decision.verification.AnalyticalVerificationOrchestrator.VerificationContext verification,
                SemanticShadowComparison semanticShadow,
                com.example.BACKEND.catalogue.decision.synthesis.answer.AnswerSynthesisOutput answerSynthesis,
                com.example.BACKEND.catalogue.decision.presentation.executive.ExecutivePresentation executivePresentation
        ) {
            this(insight, analytical, verification, semanticShadow, answerSynthesis, executivePresentation, null);
        }
    }

    // ─────────────────────────────────────────────────────
    // Execution lifecycle
    // ─────────────────────────────────────────────────────

    public record ExecutionRun(
            UUID           runId,
            String         tenantId,
            ExecutionState state,
            ExecutionStage stage,
            Instant        startedAt,
            Instant        finishedAt,
            String         errorMessage
    ) {}

    public record DecisionExecutionContext(
            UUID                runId,
            String              tenantId,
            String              question,
            Map<String, Object> requestMeta
    ) {}
}
