package com.example.BACKEND.catalogue.decision.transforms;

import java.util.Map;

/**
 * A single semantic transformation step for execution traces.
 */
public record TransformationStep(
        String stepKey,
        String title,
        String description,
        String sourceColumn,
        String outputAlias
) {
    public static TransformationStep of(String key, String title, String detail) {
        return new TransformationStep(key, title, detail, null, null);
    }

    public static TransformationStep derived(
            String key, String title, String source, String alias, String detail
    ) {
        return new TransformationStep(key, title, detail, source, alias);
    }

    public Map<String, Object> toDetails() {
        return Map.of(
                "message", description != null ? description : title,
                "source_column", sourceColumn != null ? sourceColumn : "",
                "output_alias", outputAlias != null ? outputAlias : ""
        );
    }
}
