package com.example.BACKEND.catalogue.decision.clarification;

import com.example.BACKEND.catalogue.decision.contracts.DecisionModels.IntentResolution;
import com.example.BACKEND.catalogue.decision.contracts.DecisionModels.RegistryResolutionBundle;
import com.example.BACKEND.catalogue.decision.exploration.ExploratoryAnalysisPlanner;
import com.example.BACKEND.catalogue.decision.investigation.QuestionInvestigation;
import com.example.BACKEND.catalogue.decision.investigation.QuestionInvestigationPlanner;
import com.example.BACKEND.catalogue.decision.reasoning.AnalyticalReasoningPlanner;
import com.example.BACKEND.catalogue.decision.reasoning.QuestionDrivenReasoningPlan;
import com.example.BACKEND.catalogue.decision.transforms.SemanticQueryRewriter;
import com.example.BACKEND.catalogue.decision.transforms.TransformationStep;
import com.example.BACKEND.catalogue.decision.planning.AnalysisPlan;
import com.example.BACKEND.catalogue.decision.planning.AnalyticalIntentType;
import com.example.BACKEND.catalogue.decision.planning.UniversalAnalysisPlanner;
import com.example.BACKEND.catalogue.decision.semantics.AnalyticalRelationship;
import com.example.BACKEND.catalogue.decision.semantics.MetricResolution;
import com.example.BACKEND.catalogue.decision.semantics.MetricResolutionEngine;
import com.example.BACKEND.catalogue.decision.semantics.QuestionSemanticExtractor;
import com.example.BACKEND.catalogue.decision.semantics.QuestionSemantics;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Resolves analytical questions via semantic extraction → metric resolution → reasoning plan.
 */
@Service
public class AnalyticalQuestionResolver {

    private final QuestionSemanticExtractor semanticExtractor;
    private final MetricResolutionEngine metricResolutionEngine;
    private final AnalyticalReasoningPlanner reasoningPlanner;
    private final ExploratoryAnalysisPlanner exploratoryPlanner;
    private final SemanticQueryRewriter queryRewriter;
    private final QuestionInvestigationPlanner investigationPlanner;
    private final UniversalAnalysisPlanner universalAnalysisPlanner;

    public AnalyticalQuestionResolver(
            QuestionSemanticExtractor semanticExtractor,
            MetricResolutionEngine metricResolutionEngine,
            AnalyticalReasoningPlanner reasoningPlanner,
            ExploratoryAnalysisPlanner exploratoryPlanner,
            SemanticQueryRewriter queryRewriter,
            QuestionInvestigationPlanner investigationPlanner,
            UniversalAnalysisPlanner universalAnalysisPlanner
    ) {
        this.semanticExtractor = semanticExtractor;
        this.metricResolutionEngine = metricResolutionEngine;
        this.reasoningPlanner = reasoningPlanner;
        this.exploratoryPlanner = exploratoryPlanner;
        this.queryRewriter = queryRewriter;
        this.investigationPlanner = investigationPlanner;
        this.universalAnalysisPlanner = universalAnalysisPlanner;
    }

    public ResolvedAnalyticalQuestion resolve(IntentResolution intent, RegistryResolutionBundle bundle) {
        return resolveFull(intent, bundle).resolved();
    }

    public SemanticResolution resolveFull(IntentResolution intent, RegistryResolutionBundle bundle) {
        var investigation = investigationPlanner.plan(intent.question(), bundle);
        QuestionSemantics semantics = semanticExtractor.extract(intent.question(), bundle);
        semantics = overlayInvestigationSemantics(semantics, investigation);
        MetricResolution resolution = metricResolutionEngine.resolve(semantics, bundle);
        QuestionDrivenReasoningPlan plan = reasoningPlanner.plan(semantics, resolution);
        String table = bundle != null && !bundle.entities().isEmpty()
                ? bundle.entities().getFirst().tableRef() : null;
        var transformSteps = table != null
                ? queryRewriter.collectTraceSteps(intent.question(), table, plan.queryPlan(), bundle)
                : List.<TransformationStep>of();
        plan = plan.withTransformationSteps(transformSteps);
        AnalysisPlan analysisPlan = universalAnalysisPlanner.plan(
                intent.question(), bundle, investigation, resolution, transformSteps);
        ResolvedAnalyticalQuestion resolved = exploratoryPlanner.plan(
                intent, bundle, semantics, resolution, plan);
        return new SemanticResolution(resolved, semantics, resolution, plan, investigation, analysisPlan);
    }

    public QuestionSemantics extractSemantics(String question, RegistryResolutionBundle bundle) {
        return semanticExtractor.extract(question, bundle);
    }

    public MetricResolution resolveMetrics(QuestionSemantics semantics, RegistryResolutionBundle bundle) {
        return metricResolutionEngine.resolve(semantics, bundle);
    }

    public QuestionDrivenReasoningPlan buildReasoningPlan(
            QuestionSemantics semantics, MetricResolution resolution
    ) {
        return reasoningPlanner.plan(semantics, resolution);
    }

    private QuestionSemantics overlayInvestigationSemantics(
            QuestionSemantics semantics, QuestionInvestigation investigation
    ) {
        if (semantics == null || investigation == null || investigation.extraction() == null) {
            return semantics;
        }
        var ext = investigation.extraction();
        String metric = ext.metricKey() != null ? ext.metricKey() : semantics.primaryMetric();
        String dimension = investigation.dimension() != null && investigation.dimension().resolved()
                ? investigation.dimension().columnKey()
                : semantics.dimension();
        AnalyticalIntentType intent = mapInvestigationIntent(ext.intent(), semantics.intent());
        double confidence = Math.max(semantics.confidence(), ext.confidence());

        return new QuestionSemantics(
                semantics.question(),
                metric,
                ext.metricLabel() != null ? ext.metricLabel() : semantics.primaryMetricLabel(),
                ext.targetMetricKey(),
                semantics.targetMetricLabel(),
                dimension,
                investigation.dimension() != null && investigation.dimension().displayLabel() != null
                        ? investigation.dimension().displayLabel() : semantics.dimensionLabel(),
                dimension != null ? dimension : semantics.grouping(),
                intent,
                mapRelationship(intent, semantics.relationship()),
                semantics.temporalReferences(),
                confidence,
                semantics.extractedEntities());
    }

    private AnalyticalIntentType mapInvestigationIntent(
            com.example.BACKEND.catalogue.decision.investigation.AnalyticalInvestigationIntent inv,
            AnalyticalIntentType fallback
    ) {
        if (inv == null) return fallback;
        return switch (inv) {
            case CONTRIBUTION, SHARE_OF_TOTAL -> AnalyticalIntentType.CONTRIBUTION;
            case COMPARISON -> AnalyticalIntentType.COMPARISON;
            case RANKING -> AnalyticalIntentType.RANKING;
            case TREND -> AnalyticalIntentType.TREND_ANALYSIS;
            case DISTRIBUTION -> AnalyticalIntentType.DISTRIBUTION;
            case EFFICIENCY -> AnalyticalIntentType.EFFICIENCY;
            case RELATIONSHIP -> AnalyticalIntentType.RELATIONSHIP;
            case EXACT_LOOKUP -> AnalyticalIntentType.GENERAL_ANALYSIS;
        };
    }

    private AnalyticalRelationship mapRelationship(
            AnalyticalIntentType intent, AnalyticalRelationship fallback
    ) {
        return switch (intent) {
            case RANKING -> AnalyticalRelationship.RANKING;
            case TREND_ANALYSIS, FORECASTING -> AnalyticalRelationship.TREND_OVER_TIME;
            case COMPARISON -> AnalyticalRelationship.COMPARISON;
            case DISTRIBUTION, SEGMENTATION -> AnalyticalRelationship.DISTRIBUTION;
            case EFFICIENCY -> AnalyticalRelationship.EFFICIENCY;
            case RELATIONSHIP -> AnalyticalRelationship.METRIC_RELATIONSHIP;
            default -> fallback != null ? fallback : AnalyticalRelationship.DIMENSION_BREAKDOWN;
        };
    }
}
