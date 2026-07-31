package com.example.BACKEND.catalogue.investigation;

import com.example.BACKEND.catalogue.semantic.phase2.GptSemanticPlanningOrchestrator.GptPlanningOutcome;
import com.example.BACKEND.catalogue.semantic.phase2.StructuredSemanticPlan;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;

/**
 * Resolves planner payload into a typed {@link InvestigationPlan} evidence program.
 * Legacy {@code investigation_objective} is adapted only via {@link LegacyObjectiveAdapter}.
 */
@Component
public class InvestigationPlanMapper {

    private final InvestigationPlanParser planParser;
    private final InvestigationObjectiveParser objectiveParser;
    private final LegacyObjectiveAdapter legacyObjectiveAdapter;

    public InvestigationPlanMapper(
            InvestigationPlanParser planParser,
            InvestigationObjectiveParser objectiveParser,
            LegacyObjectiveAdapter legacyObjectiveAdapter
    ) {
        this.planParser = planParser;
        this.objectiveParser = objectiveParser;
        this.legacyObjectiveAdapter = legacyObjectiveAdapter;
    }

    public InvestigationPlan map(GptPlanningOutcome outcome) {
        if (outcome == null || outcome.semanticPlan() == null) {
            return null;
        }
        StructuredSemanticPlan plan = outcome.semanticPlan();
        JsonNode rawPlan = plan.investigationPlan();
        if (rawPlan != null && !rawPlan.isNull() && !rawPlan.isMissingNode()) {
            InvestigationPlan parsed = planParser.parse(rawPlan, objectiveParser);
            if (parsed != null && parsed.evidenceRequests() != null && !parsed.evidenceRequests().isEmpty()) {
                return InvestigationPlanMetricBinder.bind(parsed, plan);
            }
        }

        JsonNode rawObjective = plan.investigationObjective();
        if (rawObjective == null || rawObjective.isNull() || rawObjective.isMissingNode()) {
            return null;
        }
        InvestigationObjective objective = objectiveParser.parse(rawObjective);
        InvestigationPlan fromLegacy = legacyObjectiveAdapter.toPlan(objective);
        return InvestigationPlanMetricBinder.bind(fromLegacy, plan);
    }
}
