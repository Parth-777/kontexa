package com.example.BACKEND.catalogue.decision.presentation.executive;

import com.example.BACKEND.catalogue.charts.ChartSpec;
import com.example.BACKEND.catalogue.decision.analytics.AnalyticalDepthResult;
import com.example.BACKEND.catalogue.decision.clarification.ResolvedAnalyticalQuestion;
import com.example.BACKEND.catalogue.decision.contracts.DecisionModels.ComputationResultSet;
import com.example.BACKEND.catalogue.decision.contracts.DecisionModels.IntentResolution;
import com.example.BACKEND.catalogue.decision.execution.ExecutionFindings;
import com.example.BACKEND.catalogue.decision.execution.materialization.AnalyticalResultType;
import com.example.BACKEND.catalogue.decision.execution.materialization.MaterializedQueryResult;
import com.example.BACKEND.catalogue.decision.planning.AnalyticalIntentType;
import com.example.BACKEND.catalogue.decision.planning.InvestigationPlan;
import com.example.BACKEND.catalogue.decision.reasoning.AnalyticalReasoningOrchestrator.ReasoningResult;
import com.example.BACKEND.catalogue.decision.reasoning.GroundedAnalyticalFinding;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
/**
 * Separates internal analytical reasoning from executive user presentation.
 */
@Service
public class ExecutivePresentationLayer {

    private final ExecutiveNarrativeEngine narrative;
    private final RevenueCompositionAnalyzer revenueAnalyzer;
    private final AnswerCompressionPolicy compression;
    private final ExecutiveEmptyStateMapper emptyStates;
    private final BusinessSemanticAliases aliases;
    private final ExploratoryExecutivePresenter exploratory;
    private final CorrelationExecutivePresenter correlationPresenter;

    public ExecutivePresentationLayer(
            ExecutiveNarrativeEngine narrative,
            RevenueCompositionAnalyzer revenueAnalyzer,
            AnswerCompressionPolicy compression,
            ExecutiveEmptyStateMapper emptyStates,
            BusinessSemanticAliases aliases,
            ExploratoryExecutivePresenter exploratory,
            CorrelationExecutivePresenter correlationPresenter
    ) {
        this.narrative = narrative;
        this.revenueAnalyzer = revenueAnalyzer;
        this.compression = compression;
        this.emptyStates = emptyStates;
        this.aliases = aliases;
        this.exploratory = exploratory;
        this.correlationPresenter = correlationPresenter;
    }

    public ExecutiveInsightCard present(
            IntentResolution intent,
            InvestigationPlan plan,
            ReasoningResult reasoning,
            ChartSpec chartSpec,
            double confidence,
            boolean recoveryMode,
            String recoveryReason,
            ResolvedAnalyticalQuestion resolvedQuestion,
            ComputationResultSet results
    ) {
        return present(intent, plan, reasoning, chartSpec, confidence, recoveryMode,
                recoveryReason, resolvedQuestion, results, null, null);
    }

    public ExecutiveInsightCard present(
            IntentResolution intent,
            InvestigationPlan plan,
            ReasoningResult reasoning,
            ChartSpec chartSpec,
            double confidence,
            boolean recoveryMode,
            String recoveryReason,
            ResolvedAnalyticalQuestion resolvedQuestion,
            ComputationResultSet results,
            AnalyticalDepthResult depthResult,
            ExecutionFindings executionFindings
    ) {
        AnalyticalIntentType intentType = plan != null
                ? plan.intentType() : AnalyticalIntentType.GENERAL_ANALYSIS;

        GroundedAnalyticalFinding primary = reasoning != null && !reasoning.prioritizedFindings().isEmpty()
                ? reasoning.prioritizedFindings().getFirst() : null;

        ExecutiveConfidenceLabel confidenceLabel =
                ExecutiveConfidenceLabel.fromScore(confidence, recoveryMode);

        if (recoveryMode) {
            return buildRecoveryCard(intent, resolvedQuestion, recoveryReason, confidenceLabel, intentType);
        }

        MaterializedQueryResult materialized = executionFindings != null
                ? executionFindings.materializedResult() : null;
        if (materialized != null
                && materialized.resultType() == AnalyticalResultType.CORRELATION_RESULT
                && materialized.correlation() != null) {
            return correlationPresenter.present(materialized.correlation(), confidenceLabel);
        }

        if (primary == null) {
            ExecutiveInsightCard exploratoryCard = exploratory.present(
                    plan, chartSpec, depthResult, executionFindings,
                    resolvedQuestion, confidence, confidenceLabel);
            if (exploratoryCard != null) {
                return exploratoryCard;
            }
            boolean hasWarehouseRows = results != null && results.results().stream()
                    .anyMatch(r -> r.rows() != null && !r.rows().isEmpty());
            boolean hasMaterialized = executionFindings != null
                    && executionFindings.materializedResult() != null
                    && executionFindings.materializedResult().hasContent();
            if (!hasWarehouseRows && !hasMaterialized) {
                return buildRecoveryCard(intent, resolvedQuestion, recoveryReason, confidenceLabel, intentType);
            }
            return buildDataAvailableCard(chartSpec, confidenceLabel, executionFindings);
        }

        RevenueCompositionAnalyzer.RevenueComposition composition = null;
        if (revenueAnalyzer.isRevenueQuestion(intent != null ? intent.question() : "")) {
            composition = revenueAnalyzer.analyze(results);
        }

        var compressed = narrative.compressedNarrative(primary, intentType);
        String title = compressed.chartTitle();
        String takeaway = compression.compressParagraph(compressed.keyTakeaway());
        String summary = compression.compressParagraph(compressed.executiveSummary());
        String chartNote = compressed.evidenceSentence().isBlank()
                ? ""
                : compression.compressParagraph(compressed.evidenceSentence());

        List<ExecutiveSupportingMetric> metrics = new ArrayList<>(narrative.metricsFromFinding(primary));
        if (composition != null && composition.hasData()) {
            for (ExecutiveSupportingMetric m : revenueAnalyzer.toMetrics(composition)) {
                if (metrics.size() >= AnswerCompressionPolicy.MAX_METRICS) break;
                if (metrics.stream().noneMatch(x -> x.label().equals(m.label()))) {
                    metrics.add(m);
                }
            }
        }
        metrics = compression.compressMetrics(metrics);

        ChartSpec chart = compression.singleChart(
                chartSpec != null ? chartSpec : primary.chartSpec());
        if (chart != null && title != null && !title.isBlank()) {
            chart.setTitle(title);
        }

        return new ExecutiveInsightCard(
                title,
                summary.isBlank() ? takeaway : summary,
                metrics,
                chart,
                takeaway,
                confidenceLabel,
                ""
        );
    }

    private ExecutiveInsightCard buildDataAvailableCard(
            ChartSpec chartSpec,
            ExecutiveConfidenceLabel confidenceLabel,
            ExecutionFindings executionFindings
    ) {
        String message = "Analysis based on warehouse query results.";
        if (executionFindings != null && executionFindings.materializedResult() != null) {
            var mat = executionFindings.materializedResult();
            if (mat.resultType() == AnalyticalResultType.SCALAR_RESULT
                    && mat.scalar() != null && !mat.findings().isEmpty()) {
                message = mat.findings().getFirst().findingText();
            } else if (mat.primaryGrouping() != null) {
                var entries = mat.primaryGrouping().rankedEntries();
                if (entries != null && !entries.isEmpty()) {
                    var top = entries.getFirst();
                    message = String.format(java.util.Locale.ROOT,
                            "Top segment %s represents %.1f%% of the grouped total.",
                            aliases.resolve(top.entityKey()), top.sharePct());
                }
            }
        }
        return new ExecutiveInsightCard(
                "Analysis", message, List.of(), chartSpec, message, confidenceLabel, "");
    }

    private ExecutiveInsightCard buildRecoveryCard(
            IntentResolution intent,
            ResolvedAnalyticalQuestion resolved,
            String recoveryReason,
            ExecutiveConfidenceLabel confidenceLabel,
            AnalyticalIntentType intentType
    ) {
        String dimension = resolved != null && resolved.assumption() != null
                ? aliases.resolve(resolved.assumption().grouping())
                : "this dimension";

        String message = emptyStates.mapRecoveryReason(recoveryReason, dimension);

        return new ExecutiveInsightCard(
                "Analysis",
                message,
                List.of(),
                null,
                message,
                confidenceLabel,
                ""
        );
    }

}
