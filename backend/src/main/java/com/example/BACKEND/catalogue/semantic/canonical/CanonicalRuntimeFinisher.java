package com.example.BACKEND.catalogue.semantic.canonical;


import com.example.BACKEND.catalogue.charts.ChartSpec;
import com.example.BACKEND.catalogue.decision.contracts.DecisionModels.ComputationResultSet;
import com.example.BACKEND.catalogue.decision.contracts.DecisionModels.DecisionRunResult;
import com.example.BACKEND.catalogue.decision.contracts.DecisionModels.InsightOutput;
import com.example.BACKEND.catalogue.decision.contracts.DecisionModels.QueryResult;
import com.example.BACKEND.catalogue.decision.contracts.DecisionModels.QuerySpec;
import com.example.BACKEND.catalogue.decision.execution.trace.ExecutionTrace;
import com.example.BACKEND.catalogue.decision.exploration.AnalyticalExecutionMode;
import com.example.BACKEND.catalogue.decision.exploration.PlannerConfidenceTier;
import com.example.BACKEND.catalogue.decision.presentation.AnalyticalResponse;
import com.example.BACKEND.catalogue.decision.presentation.EvidencePanel;
import com.example.BACKEND.catalogue.decision.presentation.ResponseMode;
import com.example.BACKEND.catalogue.decision.presentation.TableSpec;
import com.example.BACKEND.catalogue.decision.presentation.executive.ExecutiveInsightCard;
import com.example.BACKEND.catalogue.decision.presentation.executive.ExecutivePresentation;
import com.example.BACKEND.catalogue.decision.presentation.executive.ExecutivePresentationBuilder;
import com.example.BACKEND.catalogue.decision.presentation.executive.ExecutivePresentationStatisticsBuilder;
import com.example.BACKEND.catalogue.decision.presentation.executive.ExecutiveConfidenceLabel;
import com.example.BACKEND.catalogue.decision.synthesis.answer.AnswerSynthesisInputBuilder;
import com.example.BACKEND.catalogue.decision.synthesis.answer.AnswerSynthesisOutput;
import com.example.BACKEND.catalogue.decision.synthesis.answer.AnswerSynthesizer;
import com.example.BACKEND.catalogue.semantic.phase2.GptSemanticPlanningOrchestrator;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Completes decision runs on the strict canonical path without legacy materialization,
 * candidate selection, metric packs, or finding fallbacks.
 */
public final class CanonicalRuntimeFinisher {

    public static final String NO_MATCHING_DATA = "No matching data found.";

    private CanonicalRuntimeFinisher() {}

    public static int countCanonicalRows(List<QuerySpec> specs, List<QueryResult> results) {
        if (specs == null || results == null) {
            return 0;
        }
        return AnswerSynthesisInputBuilder.extractCanonicalRows(specs, results).size();
    }

    public static DecisionRunResult planNotExecutable(
            UUID runId,
            String question,
            GptSemanticPlanningOrchestrator.GptPlanningOutcome outcome,
            ExecutionTrace trace
    ) {
        String reason = outcome != null && outcome.canonicalValidation() != null
                ? String.join("; ", outcome.canonicalValidation().issues())
                : "Canonical plan not executable";
        String message = "Unable to answer from the approved catalogue: " + reason;
        return narrativeResult(runId, message, trace, null, null, null, List.of());
    }

    public static DecisionRunResult noMatchingData(UUID runId, ExecutionTrace trace) {
        return narrativeResult(runId, NO_MATCHING_DATA, trace, null, null, null, List.of());
    }

    public static DecisionRunResult withWarehouseRows(
            UUID runId,
            String question,
            List<QuerySpec> specs,
            List<QueryResult> warehouseResults,
            GptSemanticPlanningOrchestrator.GptPlanningOutcome gptOutcome,
            AnswerSynthesisInputBuilder inputBuilder,
            AnswerSynthesizer answerSynthesizer,
            ExecutivePresentationBuilder presentationBuilder,
            ExecutivePresentationStatisticsBuilder statisticsBuilder,
            ExecutionTrace trace
    ) {
        ComputationResultSet results = new ComputationResultSet(
                runId, warehouseResults, Map.of("canonical_only", true));

        List<Map<String, Object>> warehouseRows =
                AnswerSynthesisInputBuilder.extractCanonicalRows(specs, warehouseResults);
        CanonicalQueryModel canonicalModel = gptOutcome.canonicalQueryModel();

        ExecutivePresentation presentation = presentationBuilder.build(canonicalModel, warehouseRows);
        presentation = statisticsBuilder.enrich(presentation, canonicalModel, warehouseRows);

        var synthesisInput = inputBuilder.build(
                question,
                specs,
                results,
                null,
                null,
                gptOutcome.semanticPlan().confidence(),
                null,
                runId,
                canonicalModel,
                presentation,
                null);

        AnswerSynthesisOutput synthesis = answerSynthesizer.synthesize(synthesisInput)
                .orElse(AnswerSynthesisOutput.empty());

        if (!synthesis.hasContent()) {
            return narrativeResult(runId, NO_MATCHING_DATA, trace, synthesis, presentation,
                    presentationBuilder, warehouseRows);
        }
        return narrativeResult(
                runId,
                synthesis.executiveSummary(),
                trace,
                synthesis,
                presentation,
                presentationBuilder,
                warehouseRows);
    }

    private static DecisionRunResult narrativeResult(
            UUID runId,
            String narrative,
            ExecutionTrace trace,
            AnswerSynthesisOutput synthesis,
            ExecutivePresentation presentation,
            ExecutivePresentationBuilder presentationBuilder,
            List<Map<String, Object>> warehouseRows
    ) {
        String summary = narrative != null && !narrative.isBlank() ? narrative : NO_MATCHING_DATA;
        InsightOutput insight = new InsightOutput(
                runId.toString(),
                "Analysis",
                summary,
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                synthesis != null ? synthesis.confidenceExplanation() : "");

        ExecutiveInsightCard card;
        TableSpec tableSpec = TableSpec.empty();
        ChartSpec chartSpec = null;
        ResponseMode responseMode = ResponseMode.KPI;
        EvidencePanel evidencePanel = EvidencePanel.empty();

        if (presentation != null && presentation.hasContent() && presentationBuilder != null) {
            chartSpec = presentationBuilder.toChartSpec(presentation, warehouseRows);
            card = new ExecutiveInsightCard(
                    presentation.table() != null && presentation.table().title() != null
                            && !presentation.table().title().isBlank()
                            ? presentation.table().title() : "Analysis",
                    summary,
                    presentationBuilder.toSupportingMetrics(presentation),
                    chartSpec,
                    summary,
                    ExecutiveConfidenceLabel.MODERATE,
                    synthesis != null && !synthesis.keyFindings().isEmpty()
                            ? String.join(" ", synthesis.keyFindings()) : "");
            tableSpec = presentationBuilder.toTableSpec(presentation);
            responseMode = presentationBuilder.responseMode(presentation);
            evidencePanel = new EvidencePanel(
                    presentation.summary().primaryMetricLabel(),
                    presentation.summary().partitionLabel(),
                    "SUM",
                    presentation.summary().rowCount(),
                    "Canonical warehouse result set");
        } else {
            card = ExecutiveInsightCard.empty(summary, ExecutiveConfidenceLabel.MODERATE);
        }

        AnalyticalResponse analytical = new AnalyticalResponse(
                summary,
                List.of(),
                List.of(),
                chartSpec,
                new AnalyticalResponse.InsightBlock("Analysis", summary),
                synthesis != null && synthesis.hasContent() ? 0.85 : 0.5,
                evidencePanel,
                null,
                List.of(),
                false,
                List.of(),
                "",
                "",
                card,
                false,
                "",
                PlannerConfidenceTier.MEDIUM,
                AnalyticalExecutionMode.HYBRID,
                trace,
                responseMode,
                tableSpec,
                presentation != null ? presentation.type() : null,
                null);

        return new DecisionRunResult(insight, analytical, null, null, synthesis, presentation, null);
    }
}
