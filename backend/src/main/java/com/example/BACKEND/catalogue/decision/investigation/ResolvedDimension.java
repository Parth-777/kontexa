package com.example.BACKEND.catalogue.decision.investigation;

import com.example.BACKEND.catalogue.decision.transforms.SemanticConcept;

/**
 * Phase 2 output — warehouse dimension binding. Never substitutes unrelated columns.
 */
public record ResolvedDimension(
        String businessEntityPhrase,
        String columnKey,
        String groupingAlias,
        String displayLabel,
        SemanticConcept derivationConcept,
        boolean derived,
        boolean resolved,
        String failureMessage
) {
    public static ResolvedDimension unresolved(String entityPhrase, String message) {
        return new ResolvedDimension(
                entityPhrase, null, null, null, null, false, false, message);
    }

    public static ResolvedDimension physical(
            String entityPhrase, String column, String label
    ) {
        String grouping = column.endsWith("_bucket") ? column : column;
        return new ResolvedDimension(
                entityPhrase, column, grouping, label, SemanticConcept.IDENTITY, false, true, null);
    }

    public static ResolvedDimension relationshipAnalysis(String phrase, String variableKey) {
        String label = variableKey != null ? variableKey.replace('_', ' ') : "relationship variable";
        return new ResolvedDimension(
                phrase != null ? phrase : label, null, "relationship", label,
                SemanticConcept.IDENTITY, false, true, null);
    }

    public static ResolvedDimension derived(
            String entityPhrase, String sourceColumn, String groupingAlias,
            String label, SemanticConcept concept
    ) {
        return new ResolvedDimension(
                entityPhrase, sourceColumn, groupingAlias, label, concept, true, true, null);
    }
}
