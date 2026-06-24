package com.example.BACKEND.catalogue.semantic.phase2;

import com.example.BACKEND.catalogue.decision.planning.AnalysisIntent;
import com.example.BACKEND.catalogue.decision.planning.AnalysisPlan;
import com.example.BACKEND.catalogue.decision.planning.StructuredPlanProjection;
import com.example.BACKEND.catalogue.decision.semantics.catalog.SemanticDiscoveryDebug;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Converts a validated {@link StructuredSemanticPlan} into a production {@link AnalysisPlan},
 * preserving all execution-relevant structured-plan fields.
 */
@Component
public class SemanticPlanToAnalysisPlanAdapter {

    public AnalysisPlan toAnalysisPlan(
            String question,
            String tableRef,
            StructuredSemanticPlan plan,
            SemanticPlanValidationResult validation
    ) {
        return toAnalysisPlan(question, tableRef, plan, validation, null);
    }

    public AnalysisPlan toAnalysisPlan(
            String question,
            String tableRef,
            StructuredSemanticPlan plan,
            SemanticPlanValidationResult validation,
            ApprovedCatalogueSnapshot catalogue
    ) {
        if (plan == null) {
            return AnalysisPlan.blocked(question, "GPT plan missing");
        }
        if (validation != null && !validation.valid()) {
            return AnalysisPlan.blocked(question, String.join("; ", validation.issues()));
        }

        AnalysisIntent intent = mapIntent(plan.intent());
        if (intent == null) {
            return AnalysisPlan.blocked(question, "Unsupported intent: " + plan.intent());
        }

        String metric = plan.metric();
        if (metric == null || metric.isBlank()) {
            return AnalysisPlan.blocked(question, "GPT did not resolve metric");
        }

        StructuredPlanProjection projection = buildProjection(plan, catalogue);
        String dimension = projection.dimensions() != null && !projection.dimensions().isEmpty()
                ? projection.dimensions().get(0) : null;
        String grouping = dimension;

        if (intent == AnalysisIntent.RELATIONSHIP) {
            RelationshipOperands operands = resolveRelationshipOperands(plan);
            if (!operands.valid()) {
                return AnalysisPlan.blocked(question, "Relationship operands unresolved");
            }
            return new AnalysisPlan(
                    question, tableRef, intent,
                    operands.primary(), humanize(operands.primary()),
                    null, null, null,
                    operands.secondary(), humanize(operands.secondary()),
                    plan.secondaryMetric(), plan.secondaryMetric() != null
                            ? humanize(plan.secondaryMetric()) : null,
                    true, List.of(),
                    SemanticDiscoveryDebug.empty(null),
                    List.of(),
                    projection);
        }

        if (intent == AnalysisIntent.CONTRIBUTION && (dimension == null || dimension.isBlank())) {
            grouping = "composition";
        }

        List<String> blockers = new ArrayList<>();
        if (intent.requiresDimension() && intent != AnalysisIntent.CONTRIBUTION
                && (dimension == null || dimension.isBlank())) {
            blockers.add("GPT did not resolve grouping dimension");
        }

        boolean executable = blockers.isEmpty();
        return new AnalysisPlan(
                question, tableRef, intent,
                metric, humanize(metric),
                dimension, dimension != null ? humanize(dimension) : null,
                grouping,
                null, null,
                plan.secondaryMetric(), plan.secondaryMetric() != null
                        ? humanize(plan.secondaryMetric()) : null,
                executable, blockers,
                SemanticDiscoveryDebug.empty(null),
                List.of(),
                projection);
    }

    public boolean isScalarPlan(StructuredSemanticPlan plan) {
        if (plan == null || plan.intent() == null) return false;
        return "SCALAR".equalsIgnoreCase(plan.intent());
    }

    private static StructuredPlanProjection buildProjection(
            StructuredSemanticPlan plan,
            ApprovedCatalogueSnapshot catalogue
    ) {
        List<String> dims = plan.dimensions() != null ? List.copyOf(plan.dimensions()) : List.of();
        String primaryAgg = plan.aggregations() != null ? plan.aggregations().primary() : null;
        String secondaryAgg = plan.aggregations() != null ? plan.aggregations().secondary() : null;
        String orderCol = plan.ordering() != null ? plan.ordering().column() : null;
        String orderDir = plan.ordering() != null ? plan.ordering().direction() : null;
        String timeGrain = plan.timeGrain();
        if ((timeGrain == null || timeGrain.isBlank()) && catalogue != null
                && "TREND".equalsIgnoreCase(plan.intent()) && !dims.isEmpty()) {
            timeGrain = inferTimeGrain(catalogue, dims.get(0));
        }
        return new StructuredPlanProjection(
                dims,
                primaryAgg,
                secondaryAgg,
                orderCol,
                orderDir,
                plan.limit(),
                timeGrain);
    }

    private static String inferTimeGrain(ApprovedCatalogueSnapshot catalogue, String dimension) {
        if (dimension == null || catalogue == null) {
            return null;
        }
        return catalogue.columns().stream()
                .filter(c -> c.columnName().equalsIgnoreCase(dimension))
                .findFirst()
                .map(col -> {
                    String role = col.role() != null ? col.role().toLowerCase(Locale.ROOT) : "";
                    String type = col.dataType() != null ? col.dataType().toLowerCase(Locale.ROOT) : "";
                    if (role.contains("timestamp") || type.contains("date") || type.contains("timestamp")) {
                        return "MONTH";
                    }
                    return null;
                })
                .orElse(null);
    }

    /**
     * Resolves two distinct metric operands for CORR from structured-plan fields.
     */
    static RelationshipOperands resolveRelationshipOperands(StructuredSemanticPlan plan) {
        String primary = plan.metric();
        String relationship = plan.relationshipVariable();
        String secondary = plan.secondaryMetric();

        if (relationship == null || relationship.isBlank()) {
            relationship = secondary;
        }
        if (primary != null && relationship != null && primary.equalsIgnoreCase(relationship)) {
            relationship = secondary;
        }
        if (primary == null || primary.isBlank()
                || relationship == null || relationship.isBlank()
                || primary.equalsIgnoreCase(relationship)) {
            return RelationshipOperands.invalid();
        }
        return new RelationshipOperands(primary, relationship);
    }

    record RelationshipOperands(String primary, String secondary) {
        static RelationshipOperands invalid() {
            return new RelationshipOperands(null, null);
        }

        boolean valid() {
            return primary != null && secondary != null
                    && !primary.equalsIgnoreCase(secondary);
        }
    }

    private static AnalysisIntent mapIntent(String raw) {
        if (raw == null || raw.isBlank()) return AnalysisIntent.DISTRIBUTION;
        String upper = raw.toUpperCase(Locale.ROOT);
        return switch (upper) {
            case "RANKING" -> AnalysisIntent.RANKING;
            case "CONTRIBUTION" -> AnalysisIntent.CONTRIBUTION;
            case "TREND" -> AnalysisIntent.TREND;
            case "COMPARISON" -> AnalysisIntent.COMPARISON;
            case "DISTRIBUTION" -> AnalysisIntent.DISTRIBUTION;
            case "RELATIONSHIP" -> AnalysisIntent.RELATIONSHIP;
            case "SCALAR" -> AnalysisIntent.CONTRIBUTION;
            default -> null;
        };
    }

    private static String humanize(String col) {
        return col == null ? null : col.replace('_', ' ');
    }
}
