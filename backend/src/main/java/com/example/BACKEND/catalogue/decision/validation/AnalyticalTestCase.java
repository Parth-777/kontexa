package com.example.BACKEND.catalogue.decision.validation;

import com.example.BACKEND.catalogue.decision.planning.AnalyticalIntentType;

import java.util.List;

/**
 * A single golden analytical test case for the {@link ExecutionValidationHarness}.
 *
 * Each case exercises the pre-warehouse pipeline stages:
 *   schema resolution → decomposition → plan compilation → SQL generation
 *
 * The test does NOT require a live warehouse.
 * It validates that the runtime can produce correct analytical SQL from schema + intent.
 */
public record AnalyticalTestCase(
        String               id,
        String               question,
        String               intentCategory,       // human-readable: RANKING, CONTRIBUTION…
        AnalyticalIntentType expectedIntent,
        List<String>         dimensionalFocus,      // the plan's dimensional signals
        ValidationSchemaSpec schema,
        List<SqlAssertion>   sqlAssertions,         // patterns that MUST hold on generated SQL
        List<String>         mustGroupBySignals,    // strings that MUST appear in GROUP BY position
        String               description            // what this case is testing
) {

    /** A table schema for the harness to use when building the mock registry. */
    public record ValidationSchemaSpec(
            String           tableRef,
            List<ColumnSpec> columns
    ) {
        public record ColumnSpec(
                String  name,
                String  type,        // TIMESTAMP, FLOAT, INT, VARCHAR, BIGINT
                boolean isMetric,    // true → MetricDescriptor
                boolean isDimension  // true → DimensionDescriptor
        ) {}
    }

    /**
     * A single SQL assertion that the harness checks against every generated SQL statement.
     * Passes if ANY of the generated SQL statements matches the pattern.
     */
    public record SqlAssertion(
            String         id,
            String         description,
            String         pattern,         // Java regex
            AssertionType  type
    ) {
        public enum AssertionType { MUST_CONTAIN, MUST_NOT_CONTAIN }
    }
}
