package com.example.BACKEND.catalogue.decision.presentation.executive;

import com.example.BACKEND.catalogue.decision.presentation.executive.strategy.PresentationBuildContext;
import com.example.BACKEND.catalogue.decision.presentation.executive.strategy.PresentationStrategyType;
import com.example.BACKEND.catalogue.semantic.canonical.CanonicalQueryModel;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Computes deterministic business statistics from canonical warehouse rows
 * immediately after {@link ExecutivePresentationBuilder}.
 */
@Component
public class ExecutivePresentationStatisticsBuilder {

    private final PresentationBuildContext context;

    public ExecutivePresentationStatisticsBuilder(
            SemanticMetricFormatter formatter,
            ExecutivePresentationProperties properties
    ) {
        this.context = new PresentationBuildContext(formatter, properties);
    }

    public ExecutivePresentation enrich(
            ExecutivePresentation presentation,
            CanonicalQueryModel model,
            List<Map<String, Object>> warehouseRows
    ) {
        if (presentation == null || model == null || warehouseRows == null || warehouseRows.isEmpty()) {
            return presentation;
        }
        Map<String, Object> statistics = build(presentation, model, warehouseRows);
        return ExecutivePresentationFactory.withStatistics(presentation, statistics);
    }

    Map<String, Object> build(
            ExecutivePresentation presentation,
            CanonicalQueryModel model,
            List<Map<String, Object>> rows
    ) {
        PresentationStrategyType type = parseType(presentation.type());
        return switch (type) {
            case SCALAR -> scalarStats(model, rows);
            case RANKING, PARETO -> rankingStats(model, rows);
            case DISTRIBUTION -> distributionStats(model, rows);
            case TREND, GROWTH -> trendStats(model, rows);
            case COMPARISON -> comparisonStats(model, rows);
            case CONTRIBUTION -> contributionStats(model, rows);
            default -> Map.of("rowCount", rows.size());
        };
    }

    private Map<String, Object> scalarStats(CanonicalQueryModel model, List<Map<String, Object>> rows) {
        Map<String, Object> stats = new LinkedHashMap<>();
        String measureCol = context.measureColumn(model);
        Map<String, Object> row = rows.getFirst();
        double value = context.toDouble(row.get(PresentationBuildContext.findColumnKey(row, measureCol)));
        putCount(stats, rows);
        putNumber(stats, "metricValue", value);
        return stats;
    }

    private Map<String, Object> rankingStats(CanonicalQueryModel model, List<Map<String, Object>> rows) {
        Map<String, Object> stats = new LinkedHashMap<>();
        String measureCol = context.measureColumn(model);
        String partitionCol = context.partitionColumn(model, rows);
        String secondaryCol = context.secondaryColumn(model);
        String segmentCol = partitionCol != null
                ? partitionCol
                : context.firstNonMetricColumn(rows, measureCol, secondaryCol);

        List<Map<String, Object>> sorted = context.sortRows(model, rows, measureCol);
        putCount(stats, sorted);

        double total = context.sumColumn(sorted, measureCol);
        putNumber(stats, "totalAcrossRows", total);

        if (sorted.isEmpty()) {
            return stats;
        }

        Map<String, Object> leader = sorted.getFirst();
        double leaderValue = measureValue(leader, measureCol);
        putText(stats, "leaderName", segmentName(leader, segmentCol));
        putNumber(stats, "leaderValue", leaderValue);

        if (sorted.size() >= 2) {
            Map<String, Object> second = sorted.get(1);
            double secondValue = measureValue(second, measureCol);
            putText(stats, "secondName", segmentName(second, segmentCol));
            putNumber(stats, "secondValue", secondValue);

            if (!PresentationValueSanitizer.isUnavailable(leaderValue)
                    && !PresentationValueSanitizer.isUnavailable(secondValue)) {
                putNumber(stats, "valueGap", leaderValue - secondValue);
                if (secondValue != 0) {
                    putNumber(stats, "valueGapPercent", ((leaderValue - secondValue) / secondValue) * 100.0);
                }
            }
        }

        Map<String, Object> last = sorted.getLast();
        double lastValue = measureValue(last, measureCol);
        putText(stats, "lastName", segmentName(last, segmentCol));
        putNumber(stats, "lastValue", lastValue);

        if (!PresentationValueSanitizer.isUnavailable(leaderValue)
                && !PresentationValueSanitizer.isUnavailable(lastValue)) {
            putNumber(stats, "overallSpread", leaderValue - lastValue);
        }

        return stats;
    }

    private Map<String, Object> distributionStats(CanonicalQueryModel model, List<Map<String, Object>> rows) {
        Map<String, Object> stats = new LinkedHashMap<>();
        String measureCol = context.measureColumn(model);
        String partitionCol = context.partitionColumn(model, rows);
        List<Map<String, Object>> displayRows = context.capRows(rows, model);

        putCount(stats, displayRows);
        double total = context.sumColumn(displayRows, measureCol);
        putNumber(stats, "total", total);
        if (PresentationValueSanitizer.isUnavailable(total) || total == 0 || partitionCol == null) {
            return stats;
        }

        Map<String, Object> largest = null;
        Map<String, Object> smallest = null;
        double largestValue = Double.NEGATIVE_INFINITY;
        double smallestValue = Double.POSITIVE_INFINITY;

        for (Map<String, Object> row : displayRows) {
            double value = measureValue(row, measureCol);
            if (PresentationValueSanitizer.isUnavailable(value)) {
                continue;
            }
            if (value > largestValue) {
                largestValue = value;
                largest = row;
            }
            if (value < smallestValue) {
                smallestValue = value;
                smallest = row;
            }
        }

        if (largest != null) {
            putText(stats, "largestCategory", segmentName(largest, partitionCol));
            putNumber(stats, "largestShare", (largestValue / total) * 100.0);
        }
        if (smallest != null) {
            putText(stats, "smallestCategory", segmentName(smallest, partitionCol));
            putNumber(stats, "smallestShare", (smallestValue / total) * 100.0);
        }
        return stats;
    }

    private Map<String, Object> trendStats(CanonicalQueryModel model, List<Map<String, Object>> rows) {
        Map<String, Object> stats = new LinkedHashMap<>();
        String measureCol = context.measureColumn(model);
        List<Map<String, Object>> displayRows = context.capRows(rows, model);
        putCount(stats, displayRows);

        if (displayRows.size() < 2) {
            return stats;
        }

        double latest = measureValue(displayRows.getLast(), measureCol);
        double previous = measureValue(displayRows.get(displayRows.size() - 2), measureCol);
        putNumber(stats, "latestValue", latest);
        putNumber(stats, "previousValue", previous);

        if (!PresentationValueSanitizer.isUnavailable(latest)
                && !PresentationValueSanitizer.isUnavailable(previous)) {
            putNumber(stats, "absoluteGrowth", latest - previous);
            if (previous != 0) {
                putNumber(stats, "percentGrowth", ((latest - previous) / previous) * 100.0);
            }
        }
        return stats;
    }

    private Map<String, Object> comparisonStats(CanonicalQueryModel model, List<Map<String, Object>> rows) {
        Map<String, Object> stats = new LinkedHashMap<>();
        String measureCol = context.measureColumn(model);
        String secondaryCol = context.secondaryColumn(model);
        String colB = secondaryCol != null ? secondaryCol : context.detectSecondMetric(rows, measureCol);

        double metricA = context.aggregateColumn(rows, measureCol);
        double metricB = colB != null ? context.aggregateColumn(rows, colB) : Double.NaN;

        putCount(stats, rows);
        putNumber(stats, "metricA", metricA);
        putNumber(stats, "metricB", metricB);

        if (!PresentationValueSanitizer.isUnavailable(metricB)) {
            double absoluteDifference = metricB - metricA;
            putNumber(stats, "absoluteDifference", absoluteDifference);
            if (metricA != 0) {
                putNumber(stats, "percentDifference", (absoluteDifference / metricA) * 100.0);
            }
        }
        return stats;
    }

    private Map<String, Object> contributionStats(CanonicalQueryModel model, List<Map<String, Object>> rows) {
        String partitionCol = context.partitionColumn(model, rows);
        if (partitionCol != null && rows.size() > 1) {
            return distributionStats(model, rows);
        }

        Map<String, Object> stats = new LinkedHashMap<>();
        String measureCol = context.measureColumn(model);
        String secondaryCol = context.secondaryColumn(model);

        double numerator = context.aggregateColumn(rows, measureCol);
        double denominator = secondaryCol != null
                ? context.aggregateColumn(rows, secondaryCol)
                : numerator;

        putCount(stats, rows);
        putNumber(stats, "numerator", numerator);
        putNumber(stats, "denominator", denominator);

        if (!PresentationValueSanitizer.isUnavailable(denominator) && denominator != 0) {
            double contributionPercent = (numerator / denominator) * 100.0;
            putNumber(stats, "contributionPercent", contributionPercent);
            putNumber(stats, "remainingPercent", 100.0 - contributionPercent);
        }
        return stats;
    }

    private double measureValue(Map<String, Object> row, String measureCol) {
        return context.toDouble(row.get(PresentationBuildContext.findColumnKey(row, measureCol)));
    }

    private static String segmentName(Map<String, Object> row, String segmentCol) {
        if (row == null || segmentCol == null || segmentCol.isBlank()) {
            return null;
        }
        Object value = row.get(PresentationBuildContext.findColumnKey(row, segmentCol));
        return value != null ? String.valueOf(value) : null;
    }

    private static void putCount(Map<String, Object> stats, List<Map<String, Object>> rows) {
        stats.put("rowCount", rows != null ? rows.size() : 0);
    }

    private static void putNumber(Map<String, Object> stats, String key, double value) {
        if (!PresentationValueSanitizer.isUnavailable(value)) {
            stats.put(key, value);
        }
    }

    private static void putText(Map<String, Object> stats, String key, String value) {
        if (value != null && !value.isBlank()) {
            stats.put(key, value);
        }
    }

    private static PresentationStrategyType parseType(String type) {
        if (type == null || type.isBlank()) {
            return PresentationStrategyType.SCALAR;
        }
        try {
            return PresentationStrategyType.valueOf(type.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            return PresentationStrategyType.SCALAR;
        }
    }
}
