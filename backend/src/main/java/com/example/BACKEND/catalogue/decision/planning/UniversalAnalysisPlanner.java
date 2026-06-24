package com.example.BACKEND.catalogue.decision.planning;

import com.example.BACKEND.catalogue.decision.contracts.DecisionModels.RegistryResolutionBundle;
import com.example.BACKEND.catalogue.decision.investigation.AnalyticalInvestigationIntent;
import com.example.BACKEND.catalogue.decision.investigation.QuestionInvestigation;
import com.example.BACKEND.catalogue.decision.investigation.QuestionInvestigationPlanner;
import com.example.BACKEND.catalogue.decision.semantics.MetricResolution;
import com.example.BACKEND.catalogue.decision.semantics.RelationshipIntentDetector;
import com.example.BACKEND.catalogue.decision.semantics.catalog.SemanticCatalogBuilder;
import com.example.BACKEND.catalogue.decision.semantics.catalog.SemanticDiscoveryDebug;
import com.example.BACKEND.catalogue.decision.transforms.TransformationStep;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Schema-driven universal planner — produces {@link AnalysisPlan} for any registered dataset.
 *
 * Pipeline:
 *   1. Resolve schema entities from {@link RegistryResolutionBundle}
 *   2. Discover metrics and dimensions via catalog matching
 *   3. Classify intent (ranking, contribution, comparison, distribution, relationship, trend)
 *   4. Emit a single {@link AnalysisPlan} — no dataset-specific branches
 */
@Component
public class UniversalAnalysisPlanner {

    private static final Logger log = LoggerFactory.getLogger(UniversalAnalysisPlanner.class);

    private final QuestionInvestigationPlanner investigationPlanner;
    private final RelationshipIntentDetector relationshipDetector;

    public UniversalAnalysisPlanner(
            QuestionInvestigationPlanner investigationPlanner,
            RelationshipIntentDetector relationshipDetector
    ) {
        this.investigationPlanner = investigationPlanner;
        this.relationshipDetector = relationshipDetector;
    }

    public AnalysisPlan plan(String question, RegistryResolutionBundle bundle) {
        if (question == null || question.isBlank()) {
            return AnalysisPlan.blocked(question, "Empty question");
        }
        if (bundle == null || bundle.entities() == null || bundle.entities().isEmpty()) {
            return AnalysisPlan.blocked(question, "No schema entities in registry bundle");
        }

        String tableRef = bundle.entities().getFirst().tableRef();
        if (tableRef == null || tableRef.isBlank()) {
            return AnalysisPlan.blocked(question, "Schema entity has no table reference");
        }

        QuestionInvestigation investigation = investigationPlanner.plan(question, bundle);
        return plan(question, bundle, investigation, null, List.of());
    }

    public AnalysisPlan plan(
            String question,
            RegistryResolutionBundle bundle,
            QuestionInvestigation investigation,
            MetricResolution metricResolution,
            List<TransformationStep> transformations
    ) {
        if (bundle == null || bundle.entities() == null || bundle.entities().isEmpty()) {
            return AnalysisPlan.blocked(question, "No schema entities in registry bundle");
        }
        String tableRef = bundle.entities().getFirst().tableRef();

        AnalysisIntent intent = classifyIntent(question, investigation, metricResolution);
        String primaryMetric = resolvePrimaryMetric(investigation, metricResolution);
        String primaryMetricLabel = resolvePrimaryMetricLabel(investigation, metricResolution, primaryMetric);
        String dimension = resolveDimension(investigation, metricResolution);
        String dimensionLabel = resolveDimensionLabel(investigation, dimension);
        String groupingAlias = investigation != null && investigation.dimension() != null
                && investigation.dimension().resolved()
                ? investigation.dimension().groupingAlias() : dimension;

        String relationshipVar = metricResolution != null
                ? metricResolution.relationshipVariable() : null;
        String relationshipLabel = metricResolution != null
                ? metricResolution.relationshipVariableLabel() : null;
        String secondaryMetric = resolveSecondaryMetric(investigation, metricResolution);
        String secondaryMetricLabel = metricResolution != null
                ? metricResolution.targetMetricLabel() : null;
        boolean shareAnalysis = (investigation != null && investigation.extraction() != null
                && investigation.extraction().isShareAnalysis())
                || (intent == AnalysisIntent.CONTRIBUTION
                && (dimension == null || dimension.isBlank())
                && secondaryMetric != null && !secondaryMetric.isBlank());
        if (shareAnalysis) {
            dimension = null;
            dimensionLabel = null;
            groupingAlias = "composition";
        }

        List<String> blockers = new ArrayList<>();
        if (primaryMetric == null || primaryMetric.isBlank()) {
            blockers.add("Primary metric unresolved from schema");
        }
        if (intent.requiresDimension() && !shareAnalysis
                && (dimension == null || dimension.isBlank())) {
            blockers.add("Grouping dimension unresolved from schema");
        }
        if (shareAnalysis && (secondaryMetric == null || secondaryMetric.isBlank())) {
            blockers.add("Share analysis denominator metric unresolved from schema");
        }
        if (intent == AnalysisIntent.RELATIONSHIP) {
            if (relationshipVar == null || relationshipVar.isBlank()) {
                blockers.add("Relationship variable unresolved from schema");
            }
            if (primaryMetric == null || primaryMetric.isBlank()) {
                blockers.add("Target metric unresolved for relationship analysis");
            }
        }
        if (investigation != null && !investigation.executable() && blockers.isEmpty()) {
            blockers.add(investigation.blockingReason());
        }

        boolean executable = blockers.isEmpty();
        SemanticDiscoveryDebug discovery = investigation != null && investigation.discovery() != null
                ? investigation.discovery()
                : SemanticDiscoveryDebug.empty(
                        investigation != null ? investigation.catalog() : null);

        log.info("[analysis-plan] intent={} metric={} dimension={} relationship={} executable={} table={}",
                intent, primaryMetric, dimension, relationshipVar, executable, tableRef);

        return new AnalysisPlan(
                question,
                tableRef,
                intent,
                primaryMetric,
                primaryMetricLabel,
                dimension,
                dimensionLabel,
                groupingAlias,
                relationshipVar,
                relationshipLabel,
                secondaryMetric,
                secondaryMetricLabel,
                executable,
                blockers,
                discovery,
                transformations != null ? transformations : List.of(),
                StructuredPlanProjection.empty());
    }

    private AnalysisIntent classifyIntent(
            String question,
            QuestionInvestigation investigation,
            MetricResolution metricResolution
    ) {
        if (metricResolution != null && metricResolution.isRelationshipAnalysis()) {
            return AnalysisIntent.RELATIONSHIP;
        }
        if (relationshipDetector.matches(question)) {
            return AnalysisIntent.RELATIONSHIP;
        }
        if (investigation != null && investigation.extraction() != null) {
            AnalyticalInvestigationIntent inv = investigation.extraction().intent();
            if (inv == AnalyticalInvestigationIntent.RELATIONSHIP) {
                return AnalysisIntent.RELATIONSHIP;
            }
            if (inv != null) {
                return AnalysisIntent.fromInvestigation(inv);
            }
        }
        return AnalysisIntent.DISTRIBUTION;
    }

    private String resolvePrimaryMetric(
            QuestionInvestigation investigation, MetricResolution resolution
    ) {
        if (investigation != null && investigation.extraction() != null
                && investigation.extraction().isShareAnalysis()
                && investigation.extraction().metricKey() != null
                && !investigation.extraction().metricKey().isBlank()) {
            return investigation.extraction().metricKey();
        }
        if (resolution != null && resolution.primaryMetric() != null
                && !resolution.primaryMetric().isBlank()) {
            return resolution.primaryMetric();
        }
        if (investigation != null && investigation.extraction() != null
                && investigation.extraction().metricKey() != null) {
            return investigation.extraction().metricKey();
        }
        return null;
    }

    private String resolveSecondaryMetric(
            QuestionInvestigation investigation, MetricResolution resolution
    ) {
        if (investigation != null && investigation.extraction() != null
                && investigation.extraction().targetMetricKey() != null
                && !investigation.extraction().targetMetricKey().isBlank()) {
            return investigation.extraction().targetMetricKey();
        }
        if (resolution != null && resolution.targetMetric() != null
                && !resolution.targetMetric().isBlank()) {
            return resolution.targetMetric();
        }
        return null;
    }

    private String resolvePrimaryMetricLabel(
            QuestionInvestigation investigation,
            MetricResolution resolution,
            String primaryMetric
    ) {
        if (resolution != null && resolution.primaryMetricLabel() != null) {
            return resolution.primaryMetricLabel();
        }
        if (investigation != null && investigation.extraction() != null
                && investigation.extraction().metricLabel() != null) {
            return investigation.extraction().metricLabel();
        }
        return primaryMetric != null ? SemanticCatalogBuilder.humanize(primaryMetric) : null;
    }

    private String resolveDimension(
            QuestionInvestigation investigation, MetricResolution resolution
    ) {
        if (investigation != null && investigation.dimension() != null
                && investigation.dimension().resolved()) {
            return investigation.dimension().columnKey();
        }
        if (resolution != null && resolution.dimension() != null && !resolution.dimension().isBlank()) {
            return resolution.dimension();
        }
        return null;
    }

    private String resolveDimensionLabel(
            QuestionInvestigation investigation, String dimension
    ) {
        if (investigation != null && investigation.dimension() != null
                && investigation.dimension().displayLabel() != null) {
            return investigation.dimension().displayLabel();
        }
        return dimension != null ? SemanticCatalogBuilder.humanize(dimension) : null;
    }
}
