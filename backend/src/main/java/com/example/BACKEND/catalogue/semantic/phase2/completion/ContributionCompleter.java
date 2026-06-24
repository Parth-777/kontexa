package com.example.BACKEND.catalogue.semantic.phase2.completion;

import com.example.BACKEND.catalogue.semantic.phase2.ApprovedCatalogueSnapshot;
import com.example.BACKEND.catalogue.semantic.phase2.StructuredSemanticPlan;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.Optional;

/**
 * Completes missing contribution denominators from catalogue semantics.
 */
@Component
public class ContributionCompleter implements SemanticPlanEnrichment {

    @Override
    public boolean supports(StructuredSemanticPlan plan) {
        if (plan == null || plan.intent() == null || plan.metric() == null || plan.metric().isBlank()) {
            return false;
        }
        if (!"CONTRIBUTION".equalsIgnoreCase(plan.intent())) {
            return false;
        }
        return plan.secondaryMetric() == null || plan.secondaryMetric().isBlank();
    }

    @Override
    public StructuredSemanticPlan complete(
            StructuredSemanticPlan plan,
            ApprovedCatalogueSnapshot catalogue
    ) {
        Optional<String> denominator = CatalogueMetricSemantics.inferContributionDenominator(
                catalogue, plan.metric());
        if (denominator.isEmpty()) {
            return plan;
        }

        String aggregation = catalogue.columns().stream()
                .filter(c -> c.columnName().equalsIgnoreCase(denominator.get()))
                .map(ApprovedCatalogueSnapshot.CatalogueColumn::defaultAggregation)
                .filter(a -> a != null && !a.isBlank() && !"NONE".equalsIgnoreCase(a))
                .findFirst()
                .orElse("SUM");

        return StructuredSemanticPlanCopy.withSecondaryMetric(plan, denominator.get(), aggregation);
    }
}
