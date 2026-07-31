package com.example.BACKEND.catalogue.investigation;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Compatibility adapter only: converts legacy {@link InvestigationObjective}
 * into an {@link InvestigationPlan} evidence program.
 *
 * <p>Runtime must not construct or depend on InvestigationObjective semantics.
 */
@Component
public class LegacyObjectiveAdapter {

    public InvestigationPlan toPlan(InvestigationObjective objective) {
        if (objective == null || objective.comparison() == null) {
            return null;
        }
        List<InvestigationPlan.EvidenceRequest> requests = new ArrayList<>();
        requests.add(new InvestigationPlan.EvidenceRequest(
                "focal_total",
                "slice_metric",
                "FOCAL",
                objective.measure(),
                null,
                objective.comparison().focal(),
                null,
                null,
                null
        ));
        requests.add(new InvestigationPlan.EvidenceRequest(
                "baseline_total",
                "slice_metric",
                "BASELINE",
                objective.measure(),
                null,
                objective.comparison().baseline(),
                null,
                null,
                null
        ));
        List<String> dims = objective.decomposition() != null && objective.decomposition().dimensions() != null
                ? objective.decomposition().dimensions() : List.of();
        for (String dim : dims) {
            requests.add(new InvestigationPlan.EvidenceRequest(
                    "dim_" + sanitize(dim),
                    "metric_by_dimension",
                    null,
                    objective.measure(),
                    dim,
                    null,
                    objective.comparison().focal(),
                    objective.comparison().baseline(),
                    null
            ));
        }
        return new InvestigationPlan(
                new InvestigationPlan.Phenomenon(
                        objective.measure() != null ? objective.measure().column() : "legacy objective",
                        objective.measure()),
                objective.scope() != null && objective.scope().filters() != null
                        ? objective.scope().filters() : List.of(),
                List.of(),
                List.copyOf(requests),
                InvestigationPlan.Insufficiency.answerableEmpty(),
                objective.semanticConfidence()
        );
    }

    private String sanitize(String value) {
        if (value == null || value.isBlank()) {
            return "x";
        }
        return value.replaceAll("[^A-Za-z0-9]", "_");
    }
}
