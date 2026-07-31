package com.example.BACKEND.catalogue.investigation;

import com.example.BACKEND.catalogue.decision.contracts.DecisionModels.RegistryResolutionBundle;
import com.example.BACKEND.catalogue.semantic.phase2.GptSemanticPlanningOrchestrator.GptPlanningOutcome;
import com.example.BACKEND.catalogue.semantic.phase2.StructuredSemanticPlan;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;

/**
 * Resolves {@link InvestigationObjective} from the read-only GPT planning outcome.
 */
@Component
public class InvestigationObjectiveMapper {

    private final InvestigationObjectiveParser parser;

    public InvestigationObjectiveMapper(InvestigationObjectiveParser parser) {
        this.parser = parser;
    }

    public InvestigationObjective map(GptPlanningOutcome outcome) {
        if (outcome == null || outcome.semanticPlan() == null) {
            return null;
        }
        StructuredSemanticPlan plan = outcome.semanticPlan();
        JsonNode raw = plan.investigationObjective();
        if (raw != null && !raw.isNull() && !raw.isMissingNode()) {
            return parser.parse(raw);
        }
        return null;
    }

    public String resolveTableRef(GptPlanningOutcome outcome, RegistryResolutionBundle bundle) {
        if (outcome != null && outcome.analysisPlan() != null
                && outcome.analysisPlan().tableRef() != null) {
            return outcome.analysisPlan().tableRef();
        }
        if (bundle != null && bundle.entities() != null && !bundle.entities().isEmpty()) {
            return bundle.entities().get(0).tableRef();
        }
        return null;
    }
}
