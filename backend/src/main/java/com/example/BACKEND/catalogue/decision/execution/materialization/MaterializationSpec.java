package com.example.BACKEND.catalogue.decision.execution.materialization;

import com.example.BACKEND.catalogue.decision.grounding.SemanticFieldKind;

/**
 * Specifies a single grouping operation the materializer will execute.
 *
 * The materializer builds a list of these specs from the {@link
 * com.example.BACKEND.catalogue.decision.planning.InvestigationPlan} and the
 * discovered schema.  Each spec drives one in-memory GROUP BY pass.
 *
 * SpecType signals how the grouping column was produced:
 *   SOURCE_DIMENSION  — already a column in the result rows (low-cardinality string)
 *   DERIVED_TIME      — hour_of_day / weekday / month / quarter extracted from a
 *                       timestamp column at materialisation time
 *   COMPOSITE         — two SOURCE_DIMENSION columns combined into "A → B"
 *
 * priority  (0 = highest) drives the order in which specs are tried; the
 * materializer emits evidence for the highest-priority specs that actually yield
 * enough groups (≥ 3).
 */
public record MaterializationSpec(
        String   groupingKey,       // column name used for GROUP BY (may be a derived column)
        String   sourceColumn,      // the original raw column it was derived from (same if not derived)
        String   displayLabel,      // human-readable label for synthesis prompts
        SpecType specType,
        int      priority           // lower = higher priority
) {

    public enum SpecType {
        SOURCE_DIMENSION,
        DERIVED_TIME,
        COMPOSITE
    }

    /** Returns true when this spec is better suited to answer temporal questions. */
    public boolean isTemporal() { return specType == SpecType.DERIVED_TIME; }

    public SemanticFieldKind fieldKind() {
        return switch (specType) {
            case DERIVED_TIME -> SemanticFieldKind.TEMPORAL_DIMENSION;
            case COMPOSITE, SOURCE_DIMENSION -> SemanticFieldKind.CATEGORICAL_DIMENSION;
        };
    }
}
