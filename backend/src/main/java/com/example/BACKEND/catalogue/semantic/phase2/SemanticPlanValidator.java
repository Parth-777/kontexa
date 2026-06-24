package com.example.BACKEND.catalogue.semantic.phase2;



import org.springframework.stereotype.Component;



import java.util.ArrayList;

import java.util.List;

import java.util.Locale;

import java.util.Set;



/**

 * Enforces catalogue/schema contract on GPT plans. No phrase matching.

 */

@Component

public class SemanticPlanValidator {



    private static final Set<String> ALLOWED_INTENTS = Set.of(

            "RANKING", "CONTRIBUTION", "TREND", "COMPARISON", "DISTRIBUTION", "RELATIONSHIP", "SCALAR",
            "GROWTH", "PARETO", "OUTLIER", "VARIANCE");

    private static final Set<String> ALLOWED_OPS = Set.of(

            "=", "!=", "<>", ">", ">=", "<", "<=", "IN", "LIKE");

    private static final Set<String> ALLOWED_TIME_GRAINS = Set.of(

            "DAY", "WEEK", "MONTH", "QUARTER", "YEAR");



    private final SemanticPlanningProperties properties;



    public SemanticPlanValidator(SemanticPlanningProperties properties) {

        this.properties = properties;

    }



    public SemanticPlanValidationResult validate(

            StructuredSemanticPlan plan,

            ApprovedCatalogueSnapshot catalogue

    ) {

        List<String> issues = new ArrayList<>();



        if (plan == null) {

            return SemanticPlanValidationResult.fail("null plan");

        }



        String intent = plan.intent() != null ? plan.intent().toUpperCase(Locale.ROOT) : "";

        if (!ALLOWED_INTENTS.contains(intent)) {

            issues.add("INVALID_INTENT: " + plan.intent());

        }



        if (plan.confidence() < properties.getMinConfidence()) {

            issues.add("LOW_CONFIDENCE: " + plan.confidence());

        }



        if (plan.metric() != null && !plan.metric().isBlank()

                && catalogue.metricColumns().stream().noneMatch(m -> m.equalsIgnoreCase(plan.metric()))) {

            issues.add("INVALID_METRIC: " + plan.metric());

        }



        if (plan.secondaryMetric() != null && !catalogue.metricColumns().stream()

                .anyMatch(m -> m.equalsIgnoreCase(plan.secondaryMetric()))) {

            issues.add("INVALID_SECONDARY_METRIC: " + plan.secondaryMetric());

        }



        if (plan.relationshipVariable() != null) {

            if (catalogue.metricColumns().stream()

                    .noneMatch(m -> m.equalsIgnoreCase(plan.relationshipVariable()))) {

                issues.add("INVALID_RELATIONSHIP_VARIABLE: " + plan.relationshipVariable());

            }

            if (catalogue.dimensionColumns().stream()

                    .anyMatch(d -> d.equalsIgnoreCase(plan.relationshipVariable()))) {

                issues.add("RELATIONSHIP_VARIABLE_IS_DIMENSION: " + plan.relationshipVariable());

            }

        }



        for (String dim : plan.dimensions()) {

            if (!catalogue.dimensionColumns().stream().anyMatch(d -> d.equalsIgnoreCase(dim))) {

                issues.add("INVALID_DIMENSION: " + dim);

            }

        }



        for (StructuredSemanticPlan.SemanticFilter f : plan.filters()) {

            if (!catalogue.hasColumn(f.column())) {

                issues.add("INVALID_FILTER_COLUMN: " + f.column());

            }

            String op = f.operator() != null ? f.operator().toUpperCase(Locale.ROOT) : "=";

            if (!ALLOWED_OPS.contains(op)) {

                issues.add("INVALID_FILTER_OPERATOR: " + f.operator());

            }

        }



        if ("RELATIONSHIP".equals(intent)) {

            if (plan.metric() == null || plan.metric().isBlank()) {

                issues.add("MISSING_RELATIONSHIP_METRIC");

            }

            var operands = SemanticPlanToAnalysisPlanAdapter.resolveRelationshipOperands(plan);

            if (!operands.valid()) {

                issues.add("DUPLICATE_RELATIONSHIP_METRICS");

            }

        } else if (!"SCALAR".equals(intent)) {

            if (plan.metric() == null || plan.metric().isBlank()) {

                issues.add("MISSING_METRIC");

            }

            if (requiresDimension(intent) && (plan.dimensions() == null || plan.dimensions().isEmpty())) {

                issues.add("MISSING_DIMENSION");

            }

        } else {

            if (plan.metric() == null || plan.metric().isBlank()) {

                issues.add("MISSING_SCALAR_METRIC");

            }

        }



        if (plan.ordering() != null && !catalogue.hasColumn(plan.ordering().column())) {

            issues.add("INVALID_ORDERING_COLUMN: " + plan.ordering().column());

        }



        if (plan.timeGrain() != null && !plan.timeGrain().isBlank()

                && !ALLOWED_TIME_GRAINS.contains(plan.timeGrain().toUpperCase(Locale.ROOT))) {

            issues.add("INVALID_TIME_GRAIN: " + plan.timeGrain());

        }



        return issues.isEmpty()

                ? SemanticPlanValidationResult.ok()

                : SemanticPlanValidationResult.fail(issues);

    }



    private static boolean requiresDimension(String intent) {

        return switch (intent) {

            case "RANKING", "TREND", "GROWTH", "COMPARISON", "DISTRIBUTION", "PARETO", "OUTLIER", "VARIANCE" -> true;

            default -> false;

        };

    }

}


