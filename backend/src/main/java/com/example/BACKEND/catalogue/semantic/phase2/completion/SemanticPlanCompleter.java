package com.example.BACKEND.catalogue.semantic.phase2.completion;

import com.example.BACKEND.catalogue.semantic.phase2.ApprovedCatalogueSnapshot;
import com.example.BACKEND.catalogue.semantic.phase2.StructuredSemanticPlan;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Objects;

/**
 * Orchestrates modular semantic plan enrichments between GPT planning and CQM generation.
 */
@Component
public class SemanticPlanCompleter {

    private final List<SemanticPlanEnrichment> enrichments;

    public SemanticPlanCompleter(List<SemanticPlanEnrichment> enrichments) {
        this.enrichments = enrichments != null ? List.copyOf(enrichments) : List.of();
    }

    public StructuredSemanticPlan complete(
            StructuredSemanticPlan plan,
            ApprovedCatalogueSnapshot catalogue
    ) {
        if (plan == null || catalogue == null) {
            return plan;
        }
        StructuredSemanticPlan current = plan;
        for (SemanticPlanEnrichment enrichment : enrichments) {
            if (enrichment.supports(current)) {
                current = enrichment.complete(current, catalogue);
            }
        }
        return current;
    }

    public boolean changed(StructuredSemanticPlan before, StructuredSemanticPlan after) {
        return !Objects.equals(before, after);
    }
}
