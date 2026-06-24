package com.example.BACKEND.catalogue.decision.investigation;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Phase 3 — builds a question-specific investigation step list.
 */
@Component
public class InvestigationStepPlanner {

    public List<InvestigationStep> plan(
            ExtractedQuestionEntities extraction,
            ResolvedDimension dimension
    ) {
        List<InvestigationStep> steps = new ArrayList<>();
        int order = 1;

        steps.add(step(order++, "extract_entity",
                "Extract business entity",
                extraction.businessEntityPhrase() != null
                        ? "Entity: " + extraction.businessEntityPhrase()
                        : "Entity: " + extraction.metricLabel()));

        if (extraction.isShareAnalysis()) {
            steps.add(step(order++, "resolve_metrics",
                    "Resolve numerator and denominator",
                    extraction.metricKey() + " share of " + extraction.targetMetricKey()));
            steps.add(step(order++, "compute_share",
                    "Calculate revenue share",
                    "Aggregate tip vs total revenue"));
            steps.add(step(order++, "create_chart",
                    "Create composition chart",
                    "Single-ratio visualization"));
            return steps;
        }

        if (extraction.isRelationshipAnalysis()) {
            steps.add(step(order++, "resolve_relationship_variables",
                    "Resolve source and target metrics",
                    extraction.targetMetricKey() + " → " + extraction.metricKey()));
            steps.add(step(order++, "compute_correlation",
                    "Compute relationship strength",
                    "Pearson correlation at row level"));
            steps.add(step(order++, "execute_warehouse",
                    "Execute warehouse query",
                    "Run relationship SQL against warehouse"));
            steps.add(step(order++, "validate_results",
                    "Validate results",
                    "Check correlation coefficient and row count"));
            steps.add(step(order++, "create_chart",
                    "Create visualization",
                    "Scatter or correlation summary"));
            return steps;
        }

        if (!dimension.resolved()) {
            steps.add(step(order, "dimension_unresolved",
                    "Dimension resolution failed",
                    dimension.failureMessage() != null ? dimension.failureMessage() : "Unknown"));
            return steps;
        }

        steps.add(step(order++, "resolve_dimension",
                "Resolve dimension",
                dimension.displayLabel() + " → " + dimension.groupingAlias()
                        + (dimension.derived() ? " (derived)" : "")));

        steps.add(switch (extraction.intent()) {
            case CONTRIBUTION -> step(order++, "compute_contribution",
                    "Calculate segment contribution",
                    "Aggregate " + extraction.metricLabel() + " and share %");
            case COMPARISON -> step(order++, "compare_segments",
                    "Compare segment values",
                    "Side-by-side comparison across " + dimension.displayLabel());
            case RANKING -> step(order++, "rank_segments",
                    "Rank segments",
                    "Order by " + extraction.metricLabel());
            case TREND -> step(order++, "aggregate_time",
                    "Aggregate over time",
                    "Temporal trend for " + extraction.metricLabel());
            case DISTRIBUTION -> step(order++, "bucketize",
                    "Bucket and distribute",
                    "Distribution across " + dimension.displayLabel());
            case EFFICIENCY -> step(order++, "compute_efficiency",
                    "Compute efficiency ratios",
                    "Yield per unit across segments");
            case RELATIONSHIP -> step(order++, "compute_correlation",
                    "Compute metric relationship",
                    "Correlation between variables");
            default -> step(order++, "aggregate",
                    "Aggregate metric",
                    "Group by " + dimension.displayLabel());
        });

        steps.add(step(order++, "execute_warehouse",
                "Execute warehouse query",
                "Run planned SQL against BigQuery"));
        steps.add(step(order++, "validate_results",
                "Validate results",
                "Check row count and aggregation consistency"));
        steps.add(step(order++, "create_chart",
                "Create visualization",
                "Chart from validated grouped results"));
        return steps;
    }

    private InvestigationStep step(int order, String key, String title, String desc) {
        return new InvestigationStep(order, key, title, desc);
    }
}
