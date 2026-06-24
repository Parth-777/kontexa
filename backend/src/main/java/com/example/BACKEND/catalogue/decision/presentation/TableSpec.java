package com.example.BACKEND.catalogue.decision.presentation;

import java.util.List;
import java.util.Map;

/**
 * Tabular analytical output derived from grouped query results.
 */
public record TableSpec(
        List<Column>              columns,
        List<Map<String, Object>> rows,
        String                    title,
        boolean                   sortable,
        boolean                   compact
) {
    public record Column(
            String key,
            String label,
            String format
    ) {}

    public static TableSpec empty() {
        return new TableSpec(List.of(), List.of(), "", true, true);
    }

    public boolean hasContent() {
        return rows != null && !rows.isEmpty() && columns != null && !columns.isEmpty();
    }

    public Map<String, Object> toMap() {
        return Map.of(
                "columns", columns.stream().map(c -> Map.of(
                        "key", c.key(),
                        "label", c.label(),
                        "format", c.format() != null ? c.format() : "text"
                )).toList(),
                "rows", rows != null ? rows : List.of(),
                "title", title != null ? title : "",
                "sortable", sortable,
                "compact", compact
        );
    }
}
