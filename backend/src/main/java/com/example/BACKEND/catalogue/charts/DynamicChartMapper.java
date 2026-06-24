package com.example.BACKEND.catalogue.charts;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Maps SQL result rows into a ChartSpec using LLM-provided keys or heuristics. */
@Component
public class DynamicChartMapper {

    public ChartSpec toChartSpec(
            ChartSpec.ChartType type,
            String title,
            String subtitle,
            String categoryKey,
            String valueKey,
            String xKey,
            String yKey,
            String valueFormat,
            String xFormat,
            List<Map<String, Object>> rows
    ) {
        if (rows == null || rows.isEmpty()) return null;

        String cat = categoryKey != null && !categoryKey.isBlank()
                ? categoryKey : guessCategoryKey(rows.get(0));
        String val = valueKey != null && !valueKey.isBlank()
                ? valueKey : guessValueKey(rows.get(0));
        String x = xKey != null && !xKey.isBlank() ? xKey : cat;
        String y = yKey != null && !yKey.isBlank() ? yKey : val;

        List<Map<String, Object>> data = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            Map<String, Object> point = new LinkedHashMap<>();
            if (type == ChartSpec.ChartType.LINE) {
                if (row.get(x) == null || row.get(y) == null) continue;
                point.put(x, row.get(x));
                point.put(y, row.get(y));
            } else {
                if (row.get(cat) == null || row.get(val) == null) continue;
                point.put(cat, row.get(cat));
                point.put(val, row.get(val));
            }
            data.add(point);
        }
        if (data.isEmpty()) return null;

        String fmt = valueFormat != null && !valueFormat.isBlank()
                ? valueFormat : inferFormat(val, data);
        String xf = xFormat != null && !xFormat.isBlank()
                ? xFormat : (type == ChartSpec.ChartType.LINE ? "date" : "category");

        return new ChartSpec(
                type,
                title != null && !title.isBlank() ? title : "Generated chart",
                subtitle,
                type == ChartSpec.ChartType.LINE ? null : cat,
                type == ChartSpec.ChartType.LINE ? null : val,
                type == ChartSpec.ChartType.LINE ? x : null,
                type == ChartSpec.ChartType.LINE ? y : null,
                fmt,
                xf,
                data
        );
    }

    public ChartSpec.ChartType parseType(String raw) {
        if (raw == null) return ChartSpec.ChartType.BAR;
        return switch (raw.trim().toUpperCase(Locale.ROOT)) {
            case "LINE" -> ChartSpec.ChartType.LINE;
            case "DONUT", "PIE" -> ChartSpec.ChartType.DONUT;
            default -> ChartSpec.ChartType.BAR;
        };
    }

    private String guessCategoryKey(Map<String, Object> row) {
        for (String k : row.keySet()) {
            String lower = k.toLowerCase(Locale.ROOT);
            if (lower.contains("date") || lower.contains("period") || lower.contains("month")) return k;
        }
        for (String k : row.keySet()) {
            String lower = k.toLowerCase(Locale.ROOT);
            if (lower.contains("type") || lower.contains("category") || lower.contains("segment")
                    || lower.contains("name") || lower.contains("label")) return k;
        }
        return row.keySet().iterator().next();
    }

    private String guessValueKey(Map<String, Object> row) {
        for (String k : row.keySet()) {
            String lower = k.toLowerCase(Locale.ROOT);
            if (lower.contains("count") || lower.contains("total") || lower.contains("revenue")
                    || lower.contains("amount") || lower.contains("share") || lower.contains("pct")
                    || lower.contains("value") || lower.contains("sum")) return k;
        }
        List<String> keys = new ArrayList<>(row.keySet());
        return keys.size() > 1 ? keys.get(1) : keys.get(0);
    }

    private String inferFormat(String valueKey, List<Map<String, Object>> data) {
        String lower = valueKey == null ? "" : valueKey.toLowerCase(Locale.ROOT);
        if (lower.contains("pct") || lower.contains("percent") || lower.contains("share")) return "percent";
        if (lower.contains("revenue") || lower.contains("amount") || lower.contains("fare")) return "currency";
        if (data.isEmpty()) return "number";
        Object sample = data.get(0).get(valueKey);
        if (sample instanceof Number n) {
            double v = n.doubleValue();
            if (v >= 0 && v <= 1.0) return "percent";
            if (lower.contains("share") || lower.contains("pct")) return "percent";
        }
        return "number";
    }
}
