package com.example.BACKEND.catalogue.decision.presentation.executive;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Cardinality-aware chart data reducer.
 *
 * <p>Charts must maximize understanding, not display every warehouse row. This component takes the
 * <em>complete</em> warehouse rows and produces a reduced, readable set of chart points based purely
 * on the number of grouped categories and the presentation shape. It is completely generic: it never
 * inspects dataset-specific values or question text.</p>
 *
 * <ul>
 *   <li>Time-series (TREND/GROWTH/LINE) charts are never truncated.</li>
 *   <li>RANKING charts keep the Top N categories only.</li>
 *   <li>All other category charts (CONTRIBUTION, DISTRIBUTION, ...) keep the Top N and aggregate the
 *       remaining categories into a single deterministic {@code "Other"} bucket.</li>
 * </ul>
 *
 * <p>The "Other" value is computed deterministically by summing the warehouse rows that fall outside
 * the Top N. Values are never fabricated. Statistics and the executive table continue to use the full
 * warehouse result set elsewhere — only the chart visualization is simplified here.</p>
 */
public final class ChartCardinalityReducer {

    public static final String OTHER_LABEL = "Other";

    private final int topN;

    public ChartCardinalityReducer(int topN) {
        this.topN = Math.max(1, topN);
    }

    public int topN() {
        return topN;
    }

    /**
     * @param presentationType resolved presentation strategy type (e.g. RANKING, DISTRIBUTION, CONTRIBUTION, TREND)
     * @param chartType        chart hint type (e.g. DONUT, HBAR, BAR, LINE)
     * @param categoryKey      key holding the grouped category label
     * @param valueKey         key holding the numeric measure
     * @param rows             complete warehouse rows (never mutated)
     */
    public Result reduce(
            String presentationType,
            String chartType,
            String categoryKey,
            String valueKey,
            List<Map<String, Object>> rows
    ) {
        List<Map<String, Object>> source = rows == null ? List.of() : rows;
        int total = source.size();

        String type = presentationType == null ? "" : presentationType.toUpperCase(Locale.ROOT);
        boolean lineChart = chartType != null && chartType.equalsIgnoreCase("LINE");

        // Trend / time-series: never truncate — always render the complete timeline.
        if (lineChart || "TREND".equals(type) || "GROWTH".equals(type)) {
            return new Result(copy(source), total, total, 0, null);
        }

        // Without a category + value key, or with no rows, there is nothing to reduce.
        if (categoryKey == null || categoryKey.isBlank()
                || valueKey == null || valueKey.isBlank()
                || total == 0) {
            return new Result(copy(source), total, total, 0, null);
        }

        List<Map<String, Object>> sorted = sortByValueDesc(source, valueKey);

        // Cardinality within budget: render every category.
        if (total <= topN) {
            return new Result(copy(sorted), total, total, 0, null);
        }

        List<Map<String, Object>> top = copy(sorted.subList(0, topN));
        int aggregated = total - topN;

        // RANKING keeps the Top N only (no "Other" bucket).
        if ("RANKING".equals(type)) {
            return new Result(top, topN, total, 0, truncatedNotice(topN, total));
        }

        // Contribution / distribution / other category charts: aggregate the remainder into "Other".
        double otherTotal = 0;
        for (Map<String, Object> row : sorted.subList(topN, total)) {
            double value = toDouble(row.get(resolveKey(row, valueKey)));
            if (!Double.isNaN(value)) {
                otherTotal += value;
            }
        }
        Map<String, Object> other = new LinkedHashMap<>();
        other.put(categoryKey, OTHER_LABEL);
        other.put(valueKey, otherTotal);
        top.add(other);

        return new Result(top, topN, total, aggregated, aggregatedNotice(topN, total, aggregated));
    }

    private static String truncatedNotice(int displayed, int total) {
        return "Displaying Top " + displayed + " of " + total + " categories.";
    }

    private static String aggregatedNotice(int displayed, int total, int aggregated) {
        return "Displaying Top " + displayed + " of " + total + " categories. "
                + "Remaining " + aggregated + " categories are aggregated into \"" + OTHER_LABEL
                + "\" for visualization.";
    }

    private static List<Map<String, Object>> sortByValueDesc(List<Map<String, Object>> rows, String valueKey) {
        List<Map<String, Object>> sorted = new ArrayList<>(rows);
        sorted.sort(Comparator.comparingDouble(
                (Map<String, Object> row) -> {
                    double v = toDouble(row.get(resolveKey(row, valueKey)));
                    return Double.isNaN(v) ? Double.NEGATIVE_INFINITY : v;
                }).reversed());
        return sorted;
    }

    private static List<Map<String, Object>> copy(List<Map<String, Object>> rows) {
        List<Map<String, Object>> out = new ArrayList<>(rows.size());
        for (Map<String, Object> row : rows) {
            out.add(new LinkedHashMap<>(row));
        }
        return out;
    }

    private static String resolveKey(Map<String, Object> row, String key) {
        if (row == null || key == null || row.containsKey(key)) {
            return key;
        }
        for (String candidate : row.keySet()) {
            if (candidate.equalsIgnoreCase(key)) {
                return candidate;
            }
        }
        return key;
    }

    private static double toDouble(Object value) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        if (value instanceof String text) {
            try {
                return Double.parseDouble(text.trim());
            } catch (NumberFormatException ignored) {
                return Double.NaN;
            }
        }
        return Double.NaN;
    }

    /**
     * Reduced chart data plus visualization cardinality metadata.
     *
     * @param data           reduced chart rows (Top N, optionally with a trailing "Other")
     * @param displayedRows  number of original categories rendered individually
     * @param totalRows      total number of grouped categories in the warehouse result set
     * @param aggregatedRows number of categories folded into the "Other" bucket (0 when none)
     * @param notice         human-readable truncation summary, or {@code null} when nothing was reduced
     */
    public record Result(
            List<Map<String, Object>> data,
            int displayedRows,
            int totalRows,
            int aggregatedRows,
            String notice
    ) {}
}
