package com.example.BACKEND.catalogue.semantic.canonical;

import com.example.BACKEND.catalogue.decision.presentation.executive.SemanticMetricFormatter;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Formats canonical warehouse rows for executive display and GPT interpretation.
 * Read-only: never alters underlying values, computes new metrics, or calls GPT.
 */
@Component
public class CanonicalResultFormatter {

    private static final DateTimeFormatter DATE_DISPLAY =
            DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.US);

    private final SemanticMetricFormatter metrics;

    public CanonicalResultFormatter(SemanticMetricFormatter metrics) {
        this.metrics = metrics;
    }

    /**
     * @param model used only for SQL ordering metadata (rank column) and column labels — never the question
     */
    public FormattedExecutiveTable format(
            CanonicalQueryModel model,
            List<Map<String, Object>> warehouseRows
    ) {
        if (warehouseRows == null || warehouseRows.isEmpty()) {
            return FormattedExecutiveTable.empty();
        }

        boolean includeRank = model != null
                && model.ordering() != null
                && model.ordering().column() != null
                && !model.ordering().column().isBlank();

        List<String> dataKeys = orderedDataKeys(warehouseRows.getFirst().keySet());
        List<FormattedExecutiveTable.Column> columns = new ArrayList<>();
        if (includeRank) {
            columns.add(new FormattedExecutiveTable.Column("rank", "Rank", "number"));
        }
        for (String key : dataKeys) {
            columns.add(new FormattedExecutiveTable.Column(
                    key, humanize(key), detectFormat(key)));
        }

        List<Map<String, String>> formattedRows = new ArrayList<>();
        int rank = 1;
        for (Map<String, Object> row : warehouseRows) {
            Map<String, String> formatted = new LinkedHashMap<>();
            if (includeRank) {
                formatted.put("rank", String.valueOf(rank++));
            }
            for (String key : dataKeys) {
                Object raw = row.get(resolveKey(row, key));
                formatted.put(key, formatCell(raw, key));
            }
            formattedRows.add(formatted);
        }

        return new FormattedExecutiveTable(
                buildTitle(model, dataKeys),
                List.copyOf(columns),
                List.copyOf(formattedRows),
                warehouseRows.size());
    }

    private static String buildTitle(CanonicalQueryModel model, List<String> dataKeys) {
        String measure = model != null && model.measure() != null
                ? model.measure().column() : null;
        String partition = model != null && model.partition() != null
                ? model.partition().column() : null;

        if (measure != null && partition != null
                && dataKeys.stream().anyMatch(k -> k.equalsIgnoreCase(partition))) {
            return humanize(measure) + " by " + humanize(partition);
        }
        if (measure != null && dataKeys.size() == 1
                && dataKeys.getFirst().equalsIgnoreCase(measure)) {
            return humanize(measure);
        }
        if (measure != null) {
            return humanize(measure);
        }
        return "Executive results";
    }

    private String formatCell(Object raw, String columnKey) {
        if (raw == null) {
            return "—";
        }
        if (raw instanceof Boolean b) {
            return b ? "Yes" : "No";
        }
        Double numeric = toDouble(raw);
        String format = detectFormat(columnKey);
        if (numeric != null) {
            return switch (format) {
                case "currency" -> metrics.asCurrency(numeric);
                case "percent" -> formatPercent(numeric, columnKey);
                case "date" -> formatDate(raw);
                case "number" -> metrics.compactNumber(numeric);
                default -> metrics.compactNumber(numeric);
            };
        }
        return String.valueOf(raw).trim();
    }

    private static String formatPercent(double value, String columnKey) {
        String key = columnKey.toLowerCase(Locale.ROOT);
        if (key.contains("pct") || key.contains("percent") || key.contains("share")) {
            if (value <= 1.0 && value >= 0) {
                return String.format(Locale.ROOT, "%.1f%%", value * 100.0);
            }
        }
        return String.format(Locale.ROOT, "%.1f%%", value);
    }

    private static String formatDate(Object raw) {
        String text = String.valueOf(raw).trim();
        try {
            if (text.length() >= 10) {
                return LocalDate.parse(text.substring(0, 10)).format(DATE_DISPLAY);
            }
        } catch (DateTimeParseException ignored) {
            // fall through
        }
        try {
            return LocalDateTime.parse(text).toLocalDate().format(DATE_DISPLAY);
        } catch (DateTimeParseException ignored) {
            return text;
        }
    }

    private static Double toDouble(Object raw) {
        if (raw instanceof Number number) {
            return number.doubleValue();
        }
        if (raw instanceof String text) {
            try {
                return Double.parseDouble(text.trim());
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private String detectFormat(String columnKey) {
        return metrics.detectFormat(columnKey);
    }

    private static List<String> orderedDataKeys(Set<String> keys) {
        List<String> ordered = new ArrayList<>(new LinkedHashSet<>(keys));
        return List.copyOf(ordered);
    }

    private static String resolveKey(Map<String, Object> row, String column) {
        if (row.containsKey(column)) {
            return column;
        }
        for (String key : row.keySet()) {
            if (key.equalsIgnoreCase(column)) {
                return key;
            }
        }
        return column;
    }

    private static String humanize(String column) {
        if (column == null || column.isBlank()) {
            return "";
        }
        return column.replace('_', ' ');
    }
}
