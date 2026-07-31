package com.example.BACKEND.catalogue.decision.presentation.executive.strategy;

import com.example.BACKEND.catalogue.decision.presentation.executive.ExecutivePresentation;
import com.example.BACKEND.catalogue.decision.presentation.executive.ExecutivePresentationProperties;
import com.example.BACKEND.catalogue.decision.presentation.executive.PresentationValueSanitizer;
import com.example.BACKEND.catalogue.decision.presentation.executive.SemanticMetricFormatter;
import com.example.BACKEND.catalogue.schema.SqlFieldReferenceEmitter;
import com.example.BACKEND.catalogue.semantic.canonical.CanonicalQueryModel;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Shared helpers for strategy implementations. Uses only CQM shape and warehouse rows.
 */
public final class PresentationBuildContext {

    private static final int MAX_TABLE_ROWS = 50;

    private final SemanticMetricFormatter formatter;
    private final int rankingDefaultRows;

    public PresentationBuildContext(
            SemanticMetricFormatter formatter,
            ExecutivePresentationProperties properties
    ) {
        this.formatter = formatter;
        this.rankingDefaultRows = properties != null
                ? Math.max(1, properties.getRankingDefaultRows())
                : 5;
    }

    public int rankingDefaultRows() {
        return rankingDefaultRows;
    }

    public String measureColumn(CanonicalQueryModel model) {
        if (model.measure() == null || model.measure().column() == null) {
            return "";
        }
        return model.measure().column();
    }

    public String secondaryColumn(CanonicalQueryModel model) {
        if (model.ratio() != null && model.ratio().denominator() != null
                && model.ratio().denominator().column() != null) {
            return model.ratio().denominator().column();
        }
        if (model.metadata() != null && model.metadata().secondaryMetric() != null) {
            return model.metadata().secondaryMetric();
        }
        return null;
    }

    public String partitionColumn(CanonicalQueryModel model, List<Map<String, Object>> rows) {
        if (model.partition() == null || model.partition().column() == null
                || model.partition().column().isBlank()) {
            return null;
        }
        String column = model.partition().column();
        String grain = model.partition().timeGrain();
        if (grain != null && !grain.isBlank()) {
            String alias = column + "_" + grain.toLowerCase(Locale.ROOT);
            if (columnExists(rows, alias)) {
                return alias;
            }
        }
        if (columnExists(rows, column)) {
            return column;
        }
        return firstNonMetricColumn(rows, measureColumn(model), secondaryColumn(model));
    }

    public List<Map<String, Object>> sortRows(
            CanonicalQueryModel model,
            List<Map<String, Object>> rows,
            String measureCol
    ) {
        String sortCol = model.ordering() != null && model.ordering().column() != null
                ? model.ordering().column()
                : measureCol;
        boolean desc = model.ordering() == null
                || model.ordering().direction() == null
                || "DESC".equalsIgnoreCase(model.ordering().direction());

        List<Map<String, Object>> sorted = new ArrayList<>(rows);
        Comparator<Map<String, Object>> comparator = Comparator.comparingDouble(
                row -> toDouble(row.get(findColumnKey(row, sortCol))));
        if (desc) {
            comparator = comparator.reversed();
        }
        sorted.sort(comparator);
        return sorted;
    }

    public List<Map<String, Object>> capRows(List<Map<String, Object>> rows, CanonicalQueryModel model) {
        int limit = model.limit() != null
                ? model.limit()
                : Math.min(rows.size(), MAX_TABLE_ROWS);
        limit = Math.max(1, Math.min(limit, MAX_TABLE_ROWS));
        return rows.size() <= limit ? rows : rows.subList(0, limit);
    }

    public double aggregateColumn(List<Map<String, Object>> rows, String column) {
        if (column == null || column.isBlank()) {
            return Double.NaN;
        }
        if (rows.size() == 1) {
            return toDouble(rows.getFirst().get(findColumnKey(rows.getFirst(), column)));
        }
        return sumColumn(rows, column);
    }

    public double sumColumn(List<Map<String, Object>> rows, String column) {
        double total = 0;
        for (Map<String, Object> row : rows) {
            double value = toDouble(row.get(findColumnKey(row, column)));
            if (!Double.isNaN(value)) {
                total += value;
            }
        }
        return total;
    }

    public double meanColumn(List<Map<String, Object>> rows, String column) {
        if (rows == null || rows.isEmpty()) {
            return Double.NaN;
        }
        double sum = 0;
        int count = 0;
        for (Map<String, Object> row : rows) {
            double value = toDouble(row.get(findColumnKey(row, column)));
            if (!Double.isNaN(value)) {
                sum += value;
                count++;
            }
        }
        return count == 0 ? Double.NaN : sum / count;
    }

    public double stdDevColumn(List<Map<String, Object>> rows, String column) {
        double mean = meanColumn(rows, column);
        if (Double.isNaN(mean) || rows.size() < 2) {
            return Double.NaN;
        }
        double sumSq = 0;
        int count = 0;
        for (Map<String, Object> row : rows) {
            double value = toDouble(row.get(findColumnKey(row, column)));
            if (!Double.isNaN(value)) {
                double diff = value - mean;
                sumSq += diff * diff;
                count++;
            }
        }
        return count < 2 ? Double.NaN : Math.sqrt(sumSq / count);
    }

    public double zScore(double value, double mean, double stdDev) {
        if (Double.isNaN(value) || Double.isNaN(mean) || Double.isNaN(stdDev) || stdDev == 0) {
            return Double.NaN;
        }
        return (value - mean) / stdDev;
    }

    public String formatZScore(double z) {
        if (Double.isNaN(z) || Double.isInfinite(z)) {
            return "—";
        }
        return String.format(Locale.ROOT, "%.2fσ", z);
    }

    public String formatMetric(double value, String column) {
        return formatter.formatForDisplay(value, column);
    }

    public String formatShare(double share) {
        return formatter.asSharePct(share);
    }

    public String formatGrowth(double growthPct) {
        if (Double.isNaN(growthPct) || Double.isInfinite(growthPct)) {
            return "—";
        }
        String sign = growthPct > 0 ? "+" : "";
        return sign + String.format(Locale.ROOT, "%.1f%%", growthPct);
    }

    public String labelFor(String column) {
        if (column == null || column.isBlank()) {
            return "";
        }
        return column.replace('_', ' ');
    }

    public String detectSecondMetric(List<Map<String, Object>> rows, String primary) {
        if (rows == null || rows.isEmpty()) {
            return null;
        }
        for (String key : rows.getFirst().keySet()) {
            if (!key.equalsIgnoreCase(primary) && isNumericColumn(rows, key)) {
                return key;
            }
        }
        return null;
    }

    public String firstNonMetricColumn(
            List<Map<String, Object>> rows,
            String measureCol,
            String secondaryCol
    ) {
        if (rows == null || rows.isEmpty()) {
            return null;
        }
        for (String key : rows.getFirst().keySet()) {
            if (!key.equalsIgnoreCase(measureCol)
                    && (secondaryCol == null || !key.equalsIgnoreCase(secondaryCol))
                    && !isNumericColumn(rows, key)) {
                return key;
            }
        }
        for (String key : rows.getFirst().keySet()) {
            if (!key.equalsIgnoreCase(measureCol)
                    && (secondaryCol == null || !key.equalsIgnoreCase(secondaryCol))) {
                return key;
            }
        }
        return null;
    }

    public double toDouble(Object value) {
        if (value == null) {
            return Double.NaN;
        }
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

    public String displayValue(Object value) {
        return value != null ? String.valueOf(value) : "";
    }

    public String numericString(double value) {
        return PresentationValueSanitizer.numericRaw(value);
    }

    public ExecutivePresentation.KpiCard kpiCard(String label, double raw, String columnOrFormat) {
        return new ExecutivePresentation.KpiCard(
                label,
                PresentationValueSanitizer.numericRaw(raw),
                formatMetric(raw, columnOrFormat),
                "");
    }

    /**
     * Projects warehouse SELECT aliases onto CanonicalQueryModel semantic column paths.
     *
     * <p>The SQL renderer emits nested catalogue leaves as last-segment aliases
     * ({@code totals.visits → visits}, {@code trafficSource.source → source}). Presentation,
     * statistics, and charts all look up CQM paths — this alignment is the single remapping
     * after warehouse execution so every artifact shares one analytical key space.
     */
    public static List<Map<String, Object>> alignWarehouseKeysToCanonical(
            CanonicalQueryModel model,
            List<Map<String, Object>> rows
    ) {
        if (rows == null || rows.isEmpty() || model == null) {
            return rows == null ? List.of() : rows;
        }
        Set<String> semanticColumns = new LinkedHashSet<>();
        if (model.measure() != null && model.measure().column() != null) {
            semanticColumns.add(model.measure().column());
        }
        if (model.partition() != null && model.partition().column() != null) {
            semanticColumns.add(model.partition().column());
        }
        if (model.ratio() != null && model.ratio().denominator() != null
                && model.ratio().denominator().column() != null) {
            semanticColumns.add(model.ratio().denominator().column());
        }
        if (model.ordering() != null && model.ordering().column() != null) {
            semanticColumns.add(model.ordering().column());
        }
        if (model.metadata() != null && model.metadata().secondaryMetric() != null) {
            semanticColumns.add(model.metadata().secondaryMetric());
        }
        if (model.bivariate() != null) {
            if (model.bivariate().columnA() != null) {
                semanticColumns.add(model.bivariate().columnA());
            }
            if (model.bivariate().columnB() != null) {
                semanticColumns.add(model.bivariate().columnB());
            }
        }
        if (semanticColumns.isEmpty()) {
            return rows;
        }

        List<Map<String, Object>> aligned = new ArrayList<>(rows.size());
        for (Map<String, Object> row : rows) {
            Map<String, Object> copy = new LinkedHashMap<>(row);
            for (String semantic : semanticColumns) {
                projectAliasOntoSemantic(copy, semantic);
            }
            aligned.add(copy);
        }
        return aligned;
    }

    public static String findColumnKey(Map<String, Object> row, String column) {
        if (column == null || row == null) {
            return column;
        }
        if (row.containsKey(column)) {
            return column;
        }
        for (String key : row.keySet()) {
            if (key.equalsIgnoreCase(column)) {
                return key;
            }
        }
        // SQL renderer aliases nested paths to the last segment (visits for totals.visits).
        String alias = sqlAlias(column);
        if (alias != null && !alias.equals(column)) {
            if (row.containsKey(alias)) {
                return alias;
            }
            for (String key : row.keySet()) {
                if (key.equalsIgnoreCase(alias)) {
                    return key;
                }
            }
        }
        return column;
    }

    private static void projectAliasOntoSemantic(Map<String, Object> row, String semantic) {
        if (semantic == null || semantic.isBlank()) {
            return;
        }
        if (row.containsKey(semantic)) {
            return;
        }
        for (String key : row.keySet()) {
            if (key.equalsIgnoreCase(semantic)) {
                row.put(semantic, row.get(key));
                return;
            }
        }
        String alias = sqlAlias(semantic);
        if (alias == null || alias.equals(semantic)) {
            return;
        }
        if (row.containsKey(alias)) {
            row.put(semantic, row.get(alias));
            return;
        }
        for (String key : row.keySet()) {
            if (key.equalsIgnoreCase(alias)) {
                row.put(semantic, row.get(key));
                return;
            }
        }
    }

    private static String sqlAlias(String column) {
        if (column == null || column.isBlank() || !column.contains(".")) {
            return column;
        }
        try {
            return SqlFieldReferenceEmitter.emitAlias(column);
        } catch (IllegalArgumentException ignored) {
            int dot = column.lastIndexOf('.');
            return dot >= 0 && dot < column.length() - 1
                    ? column.substring(dot + 1)
                    : column;
        }
    }

    public ExecutivePresentation.PresentationSummary summary(
            PresentationStrategyType type,
            int rowCount,
            int displayedRowCount,
            String primaryMetricLabel,
            String partitionLabel,
            String tableTitle,
            String recommendedChart,
            List<String> highlights
    ) {
        return new ExecutivePresentation.PresentationSummary(
                type.name(),
                rowCount,
                displayedRowCount,
                primaryMetricLabel,
                partitionLabel,
                tableTitle,
                recommendedChart,
                highlights);
    }

    public ExecutivePresentation.ChartHint chartHint(
            String type, String title, String categoryKey, String valueKey, String valueFormat
    ) {
        return new ExecutivePresentation.ChartHint(type, title, categoryKey, valueKey, null, null, valueFormat);
    }

    public ExecutivePresentation.PresentationTable emptyTable() {
        return new ExecutivePresentation.PresentationTable("", List.of(), List.of());
    }

    private static boolean columnExists(List<Map<String, Object>> rows, String column) {
        if (column == null || column.isBlank() || rows == null || rows.isEmpty()) {
            return false;
        }
        Map<String, Object> row = rows.getFirst();
        String resolved = findColumnKey(row, column);
        return resolved != null && row.containsKey(resolved);
    }

    private static boolean isNumericColumn(List<Map<String, Object>> rows, String key) {
        for (Map<String, Object> row : rows) {
            double value = toDoubleStatic(row.get(key));
            if (!Double.isNaN(value)) {
                return true;
            }
        }
        return false;
    }

    private static double toDoubleStatic(Object value) {
        if (value == null) {
            return Double.NaN;
        }
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
}
