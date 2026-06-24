package com.example.BACKEND.catalogue.decision.presentation.executive.strategy;

import com.example.BACKEND.catalogue.decision.presentation.executive.ExecutivePresentation;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class PresentationTableSupport {

    private PresentationTableSupport() {}

    static ExecutivePresentation.PresentationTable rankedTable(
            PresentationBuildContext ctx,
            List<Map<String, Object>> rows,
            String measureCol,
            String partitionCol,
            String measureLabel,
            String partitionLabel,
            String title
    ) {
        List<ExecutivePresentation.PresentationColumn> columns = new ArrayList<>();
        columns.add(new ExecutivePresentation.PresentationColumn("rank", "Rank", "number"));
        if (partitionCol != null) {
            columns.add(new ExecutivePresentation.PresentationColumn("segment", partitionLabel, "text"));
        }
        columns.add(new ExecutivePresentation.PresentationColumn("value", measureLabel, "currency"));

        List<Map<String, String>> formattedRows = new ArrayList<>();
        int rank = 1;
        for (Map<String, Object> row : rows) {
            Map<String, String> out = new LinkedHashMap<>();
            out.put("rank", String.valueOf(rank++));
            if (partitionCol != null) {
                out.put("segment", ctx.displayValue(row.get(PresentationBuildContext.findColumnKey(row, partitionCol))));
            }
            double value = ctx.toDouble(row.get(PresentationBuildContext.findColumnKey(row, measureCol)));
            out.put("value", ctx.formatMetric(value, measureCol));
            formattedRows.add(out);
        }
        return new ExecutivePresentation.PresentationTable(title, List.copyOf(columns), formattedRows);
    }

    static ExecutivePresentation.PresentationTable groupedTable(
            PresentationBuildContext ctx,
            List<Map<String, Object>> rows,
            String measureCol,
            String partitionCol,
            String measureLabel,
            String partitionLabel,
            String title,
            boolean includeShare
    ) {
        double total = includeShare ? ctx.sumColumn(rows, measureCol) : 0;

        List<ExecutivePresentation.PresentationColumn> columns = new ArrayList<>();
        if (partitionCol != null) {
            columns.add(new ExecutivePresentation.PresentationColumn("segment", partitionLabel, "text"));
        }
        columns.add(new ExecutivePresentation.PresentationColumn("value", measureLabel, "currency"));
        if (includeShare) {
            columns.add(new ExecutivePresentation.PresentationColumn("share_pct", "Share", "percent"));
        }

        List<Map<String, String>> formattedRows = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            Map<String, String> out = new LinkedHashMap<>();
            if (partitionCol != null) {
                out.put("segment", ctx.displayValue(row.get(PresentationBuildContext.findColumnKey(row, partitionCol))));
            }
            double value = ctx.toDouble(row.get(PresentationBuildContext.findColumnKey(row, measureCol)));
            out.put("value", ctx.formatMetric(value, measureCol));
            if (includeShare) {
                double share = total != 0 ? (value / total) * 100.0 : Double.NaN;
                out.put("share_pct", ctx.formatShare(share));
            }
            formattedRows.add(out);
        }
        return new ExecutivePresentation.PresentationTable(title, List.copyOf(columns), formattedRows);
    }

    static ExecutivePresentation.PresentationTable trendTable(
            PresentationBuildContext ctx,
            List<Map<String, Object>> rows,
            String measureCol,
            String partitionCol,
            String measureLabel,
            String partitionLabel,
            String title
    ) {
        List<ExecutivePresentation.PresentationColumn> columns = new ArrayList<>();
        if (partitionCol != null) {
            columns.add(new ExecutivePresentation.PresentationColumn("period", partitionLabel, "text"));
        }
        columns.add(new ExecutivePresentation.PresentationColumn("value", measureLabel, "currency"));
        columns.add(new ExecutivePresentation.PresentationColumn("growth_pct", "Growth", "percent"));

        List<Map<String, String>> formattedRows = new ArrayList<>();
        Double previous = null;
        for (Map<String, Object> row : rows) {
            Map<String, String> out = new LinkedHashMap<>();
            if (partitionCol != null) {
                out.put("period", ctx.displayValue(row.get(PresentationBuildContext.findColumnKey(row, partitionCol))));
            }
            double value = ctx.toDouble(row.get(PresentationBuildContext.findColumnKey(row, measureCol)));
            out.put("value", ctx.formatMetric(value, measureCol));
            if (previous == null || previous == 0) {
                out.put("growth_pct", "—");
            } else {
                double growth = ((value - previous) / previous) * 100.0;
                out.put("growth_pct", ctx.formatGrowth(growth));
            }
            previous = value;
            formattedRows.add(out);
        }
        return new ExecutivePresentation.PresentationTable(title, List.copyOf(columns), formattedRows);
    }

    static ExecutivePresentation.PresentationTable paretoTable(
            PresentationBuildContext ctx,
            List<Map<String, Object>> rows,
            String measureCol,
            String partitionCol,
            String measureLabel,
            String partitionLabel,
            String title
    ) {
        double total = ctx.sumColumn(rows, measureCol);
        List<ExecutivePresentation.PresentationColumn> columns = List.of(
                new ExecutivePresentation.PresentationColumn("rank", "Rank", "number"),
                new ExecutivePresentation.PresentationColumn("segment", partitionLabel, "text"),
                new ExecutivePresentation.PresentationColumn("value", measureLabel, "currency"),
                new ExecutivePresentation.PresentationColumn("share_pct", "Share", "percent"),
                new ExecutivePresentation.PresentationColumn("cumulative_pct", "Cumulative", "percent"));

        List<Map<String, String>> formattedRows = new ArrayList<>();
        double cumulative = 0;
        int rank = 1;
        for (Map<String, Object> row : rows) {
            Map<String, String> out = new LinkedHashMap<>();
            out.put("rank", String.valueOf(rank++));
            out.put("segment", ctx.displayValue(row.get(PresentationBuildContext.findColumnKey(row, partitionCol))));
            double value = ctx.toDouble(row.get(PresentationBuildContext.findColumnKey(row, measureCol)));
            out.put("value", ctx.formatMetric(value, measureCol));
            double share = total != 0 ? (value / total) * 100.0 : Double.NaN;
            cumulative += Double.isNaN(share) ? 0 : share;
            out.put("share_pct", ctx.formatShare(share));
            out.put("cumulative_pct", ctx.formatShare(Math.min(cumulative, 100.0)));
            formattedRows.add(out);
        }
        return new ExecutivePresentation.PresentationTable(title, columns, formattedRows);
    }

    static ExecutivePresentation.PresentationTable outlierTable(
            PresentationBuildContext ctx,
            List<Map<String, Object>> rows,
            String measureCol,
            String partitionCol,
            String measureLabel,
            String partitionLabel,
            String title,
            double mean,
            double stdDev,
            double sigmaThreshold
    ) {
        List<ExecutivePresentation.PresentationColumn> columns = List.of(
                new ExecutivePresentation.PresentationColumn("segment", partitionLabel, "text"),
                new ExecutivePresentation.PresentationColumn("value", measureLabel, "currency"),
                new ExecutivePresentation.PresentationColumn("z_score", "Z-score", "number"),
                new ExecutivePresentation.PresentationColumn("outlier_flag", "Outlier", "text"));

        List<Map<String, String>> formattedRows = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            double value = ctx.toDouble(row.get(PresentationBuildContext.findColumnKey(row, measureCol)));
            double z = ctx.zScore(value, mean, stdDev);
            boolean outlier = !Double.isNaN(z) && Math.abs(z) >= sigmaThreshold;
            Map<String, String> out = new LinkedHashMap<>();
            out.put("segment", ctx.displayValue(row.get(PresentationBuildContext.findColumnKey(row, partitionCol))));
            out.put("value", ctx.formatMetric(value, measureCol));
            out.put("z_score", ctx.formatZScore(z));
            out.put("outlier_flag", outlier ? "Yes" : "No");
            formattedRows.add(out);
        }
        return new ExecutivePresentation.PresentationTable(title, columns, formattedRows);
    }

    static ExecutivePresentation.PresentationTable varianceTable(
            PresentationBuildContext ctx,
            List<Map<String, Object>> rows,
            String measureCol,
            String partitionCol,
            String measureLabel,
            String partitionLabel,
            String title,
            double mean,
            double stdDev
    ) {
        double cv = mean != 0 && !Double.isNaN(stdDev) ? (stdDev / Math.abs(mean)) * 100.0 : Double.NaN;
        List<ExecutivePresentation.PresentationColumn> columns = List.of(
                new ExecutivePresentation.PresentationColumn("segment", partitionLabel, "text"),
                new ExecutivePresentation.PresentationColumn("value", measureLabel, "currency"),
                new ExecutivePresentation.PresentationColumn("deviation", "Deviation from mean", "currency"),
                new ExecutivePresentation.PresentationColumn("portfolio_cv", "Portfolio CV", "percent"));

        List<Map<String, String>> formattedRows = new ArrayList<>();
        String cvFormatted = Double.isNaN(cv) ? "—" : ctx.formatShare(cv);
        for (Map<String, Object> row : rows) {
            double value = ctx.toDouble(row.get(PresentationBuildContext.findColumnKey(row, measureCol)));
            double deviation = Double.isNaN(mean) ? Double.NaN : value - mean;
            Map<String, String> out = new LinkedHashMap<>();
            out.put("segment", ctx.displayValue(row.get(PresentationBuildContext.findColumnKey(row, partitionCol))));
            out.put("value", ctx.formatMetric(value, measureCol));
            out.put("deviation", ctx.formatMetric(deviation, measureCol));
            out.put("portfolio_cv", cvFormatted);
            formattedRows.add(out);
        }
        return new ExecutivePresentation.PresentationTable(title, columns, formattedRows);
    }
}
