package com.example.BACKEND.catalogue.decision.planning;

import com.example.BACKEND.catalogue.decision.clarification.ResolvedAnalyticalQuestion;
import com.example.BACKEND.catalogue.decision.contracts.DecisionModels.IntentResolution;
import com.example.BACKEND.catalogue.decision.reasoning.QuestionDrivenReasoningPlan;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

@Service
public class AnalyticalPlanningEngine {

    private static final Logger log = LoggerFactory.getLogger(AnalyticalPlanningEngine.class);

    private final AnalyticalIntentClassifier  intentClassifier;
    private final QueryDecompositionEngine    decompositionEngine;
    private final MetricRequirementResolver   metricResolver;
    private final ComparativeFrameworkBuilder frameworkBuilder;
    private final InvestigationPlanner        investigationPlanner;

    public AnalyticalPlanningEngine(
            AnalyticalIntentClassifier  intentClassifier,
            QueryDecompositionEngine    decompositionEngine,
            MetricRequirementResolver   metricResolver,
            ComparativeFrameworkBuilder frameworkBuilder,
            InvestigationPlanner        investigationPlanner
    ) {
        this.intentClassifier     = intentClassifier;
        this.decompositionEngine  = decompositionEngine;
        this.metricResolver       = metricResolver;
        this.frameworkBuilder     = frameworkBuilder;
        this.investigationPlanner = investigationPlanner;
    }

    public InvestigationPlan plan(IntentResolution intent) {
        throw new AnalysisPlanProjectionException(
                "AnalysisPlan is required — call plan(intent, analysisPlan, resolved, questionDrivenPlan)");
    }

    /** @deprecated requires AnalysisPlan — kept for tests that still use legacy resolution path */
    public InvestigationPlan plan(IntentResolution intent, ResolvedAnalyticalQuestion resolved) {
        return planLegacy(intent, resolved, null);
    }

    public InvestigationPlan plan(
            IntentResolution intent,
            ResolvedAnalyticalQuestion resolved,
            QuestionDrivenReasoningPlan questionDrivenPlan
    ) {
        throw new AnalysisPlanProjectionException(
                "AnalysisPlan is required — call plan(intent, analysisPlan, resolved, questionDrivenPlan)");
    }

    public InvestigationPlan plan(
            IntentResolution intent,
            AnalysisPlan analysisPlan,
            ResolvedAnalyticalQuestion resolved,
            QuestionDrivenReasoningPlan questionDrivenPlan
    ) {
        if (analysisPlan == null) {
            throw new AnalysisPlanProjectionException("AnalysisPlan is required for investigation planning");
        }

        String planId = "PLAN-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        AnalyticalIntentType intentType = analysisPlan.intent().toAnalyticalIntentType();
        AnalyticalReasoningPlan reasoningPlan = decompositionEngine.decomposeFromAnalysisPlan(analysisPlan);

        List<MetricRequirement> metrics = metricResolver.resolve(intentType);
        ComparativeFramework framework = frameworkBuilder.build(intentType);
        List<InvestigationStep> steps = decompositionEngine.toInvestigationSteps(reasoningPlan);
        PlanDepth depth = investigationPlanner.depthFor(intentType);
        List<String> dimensionalFocus = buildDimensionalFocus(intentType, reasoningPlan);
        String rationale = buildRationale(intent, intentType, depth, metrics.size(),
                steps.size(), reasoningPlan, resolved, analysisPlan);

        log.info("[planning] runId={} planId={} intentType={} metric={} grouping={} projected=true",
                intent.runId(), planId, intentType,
                analysisPlan.primaryMetric(),
                AnalysisPlanProjection.groupingColumn(analysisPlan));

        return new InvestigationPlan(
                planId, intentType, depth,
                steps, metrics, framework,
                dimensionalFocus, rationale,
                reasoningPlan, resolved, questionDrivenPlan);
    }

    private InvestigationPlan planLegacy(
            IntentResolution intent,
            ResolvedAnalyticalQuestion resolved,
            QuestionDrivenReasoningPlan questionDrivenPlanIgnored
    ) {
        String planId = "PLAN-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();

        AnalyticalIntentType intentType = resolved != null && resolved.assumption() != null
                ? resolved.assumption().intent().canonical()
                : intentClassifier.classify(intent).canonical();

        AnalyticalReasoningPlan reasoningPlan = resolved != null && resolved.assumption() != null
                ? decompositionEngine.decomposeFromResolution(intent, resolved)
                : decompositionEngine.decompose(intent, intentType);

        List<MetricRequirement> metrics = metricResolver.resolve(intentType);
        ComparativeFramework framework = frameworkBuilder.build(intentType);
        List<InvestigationStep> steps = decompositionEngine.toInvestigationSteps(reasoningPlan);
        PlanDepth depth = investigationPlanner.depthFor(intentType);
        List<String> dimensionalFocus = buildDimensionalFocus(intentType, reasoningPlan);
        String rationale = buildRationale(intent, intentType, depth, metrics.size(),
                steps.size(), reasoningPlan, resolved, null);

        log.info("[planning] runId={} planId={} intentType={} metric={} grouping={} ambiguous={}",
                intent.runId(), planId, intentType,
                resolved != null && resolved.assumption() != null
                        ? resolved.assumption().primaryMetric() : "inferred",
                resolved != null && resolved.assumption() != null
                        ? resolved.assumption().grouping() : "inferred",
                resolved != null && resolved.assumption() != null && resolved.assumption().ambiguous());

        return new InvestigationPlan(
                planId, intentType, depth,
                steps, metrics, framework,
                dimensionalFocus, rationale,
                reasoningPlan, resolved, questionDrivenPlanIgnored);
    }

    private List<String> buildDimensionalFocus(AnalyticalIntentType intentType,
                                                AnalyticalReasoningPlan reasoningPlan) {
        if (reasoningPlan != null && reasoningPlan.groupingDimension() != null) {
            return List.of(
                    reasoningPlan.groupingDimension().toLowerCase(),
                    reasoningPlan.comparisonMode(),
                    reasoningPlan.primaryMetric().toLowerCase()
            );
        }
        return switch (intentType) {
            case CONTRIBUTION, COMPOSITION     -> List.of("segment", "total", "share");
            case RANKING                       -> List.of("entity", "percentile", "efficiency");
            case STRATEGIC_PRIORITIZATION      -> List.of("entity", "composite_score", "tier", "concentration");
            case DISTRIBUTION, SEGMENTATION    -> List.of("segment", "distribution", "concentration");
            case COMPARISON                    -> List.of("current_period", "reference_period", "delta");
            case ANOMALY_DETECTION             -> List.of("deviation", "persistence", "affected_segment");
            case TREND_ANALYSIS                -> List.of("time_period", "growth_rate", "acceleration");
            case EFFICIENCY                    -> List.of("entity", "efficiency_ratio", "yield");
            case CORRELATION                   -> List.of("driver", "covariation", "strength");
            case RETENTION                     -> List.of("cohort", "retention_rate", "period");
            case ROOT_CAUSE_INVESTIGATION      -> List.of("driver", "volume_effect", "rate_effect", "breadth");
            case FORECASTING                   -> List.of("trend", "volatility", "projection");
            default                            -> List.of("entity", "value", "comparison");
        };
    }

    private String buildRationale(IntentResolution intent, AnalyticalIntentType intentType,
                                   PlanDepth depth, int metricCount, int stepCount,
                                   AnalyticalReasoningPlan reasoningPlan,
                                   ResolvedAnalyticalQuestion resolved,
                                   AnalysisPlan analysisPlan) {
        String assumptionNote = resolved != null && resolved.assumption() != null
                ? " Assumptions: " + String.join("; ", resolved.assumption().assumptions()) + "."
                : "";
        String projectionNote = analysisPlan != null
                ? " Projected from AnalysisPlan metric=" + analysisPlan.primaryMetric()
                + " grouping=" + AnalysisPlanProjection.groupingColumn(analysisPlan) + "."
                : "";
        return String.format(
                "Question classified as %s (confidence=%.0f%%). %s investigation: %d decomposition steps, " +
                "%d metric requirements. Reasoning plan: %s.%s%s",
                intentType.name(),
                intent.confidence() * 100,
                depth.name(),
                stepCount, metricCount,
                reasoningPlan != null ? reasoningPlan.planSummary() : "n/a",
                assumptionNote,
                projectionNote
        );
    }
}
