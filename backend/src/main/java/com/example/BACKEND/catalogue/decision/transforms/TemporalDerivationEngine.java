package com.example.BACKEND.catalogue.decision.transforms;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Derives temporal dimensions from timestamp columns.
 */
@Component
public class TemporalDerivationEngine {

    private final WarehouseDialect dialect;

    public TemporalDerivationEngine(BigQueryDialect dialect) {
        this.dialect = dialect;
    }

    public DerivedDimensionSpec derive(
            SemanticConcept concept, String timestampColumn, String logicalKey
    ) {
        List<TransformationStep> steps = new ArrayList<>();
        steps.add(TransformationStep.derived(
                "resolve_timestamp", "Resolving timestamp column",
                timestampColumn, null, "Using " + timestampColumn + " for temporal derivation"));

        String expression;
        String alias;
        String deriveTitle;

        switch (concept) {
            case WEEKEND_DAY -> {
                expression = dialect.weekendDayType(timestampColumn);
                alias = "weekend_flag";
                deriveTitle = "Deriving weekend vs weekday";
            }
            case WEEKDAY -> {
                expression = dialect.weekdayName(timestampColumn);
                alias = "weekday";
                deriveTitle = "Deriving day of week";
            }
            case HOUR_OF_DAY -> {
                expression = dialect.extractHour(timestampColumn);
                alias = "hour_of_day";
                deriveTitle = "Deriving hour of day";
            }
            case DAY_OF_WEEK -> {
                expression = dialect.extractDayOfWeek(timestampColumn);
                alias = "day_of_week";
                deriveTitle = "Deriving day of week number";
            }
            case WEEK -> {
                expression = dialect.extractWeek(timestampColumn);
                alias = "week";
                deriveTitle = "Deriving week bucket";
            }
            case MONTH -> {
                expression = dialect.extractMonth(timestampColumn);
                alias = "month";
                deriveTitle = "Deriving monthly bucket";
            }
            case QUARTER -> {
                expression = dialect.extractQuarter(timestampColumn);
                alias = "quarter";
                deriveTitle = "Deriving quarterly bucket";
            }
            case YEAR -> {
                expression = dialect.extractYear(timestampColumn);
                alias = "year";
                deriveTitle = "Deriving yearly bucket";
            }
            default -> throw new IllegalArgumentException("Not a temporal concept: " + concept);
        }

        steps.add(TransformationStep.derived(
                "derive_temporal", deriveTitle, timestampColumn, alias, expression));

        return new DerivedDimensionSpec(
                concept, logicalKey, timestampColumn, expression, alias, true, steps);
    }
}
