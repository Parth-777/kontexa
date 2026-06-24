package com.example.BACKEND.catalogue.semantic.canonical;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Deterministic executive table derived from canonical warehouse rows.
 */
public record FormattedExecutiveTable(
        String title,
        List<Column> columns,
        List<Map<String, String>> formattedRows,
        int rowCount
) {
    public record Column(String key, String label, String format) {}

    public static FormattedExecutiveTable empty() {
        return new FormattedExecutiveTable("", List.of(), List.of(), 0);
    }

    public boolean hasContent() {
        return formattedRows != null && !formattedRows.isEmpty()
                && columns != null && !columns.isEmpty();
    }

    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("title", title != null ? title : "");
        m.put("columns", columns.stream().map(c -> Map.of(
                "key", c.key(),
                "label", c.label(),
                "format", c.format() != null ? c.format() : "text"
        )).toList());
        m.put("formatted_rows", formattedRows != null ? formattedRows : List.of());
        m.put("row_count", rowCount);
        return m;
    }
}
