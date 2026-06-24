package com.example.BACKEND.catalogue.decision.evidence;

import com.example.BACKEND.catalogue.decision.contracts.DecisionModels.*;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Builds a hierarchical investigation tree from computed evidence.
 *
 * This is NOT an autonomous agent or recursive planner.
 * It is structured analytical reasoning:
 *
 *   Signal (top-level anomaly / change)
 *     → Segment breakdown (which dimension drives it?)
 *       → Concentration analysis (is impact concentrated or broad?)
 *         → Directional interpretation (up/down/anomalous)
 *
 * The tree is derived entirely from existing {@link ComputationResultSet} data.
 * No additional warehouse queries are fired.
 *
 * Example output:
 *   Revenue declined 18% MoM
 *   → Top-contributing segment: "West" (-$2.3M, 67% of total decline)
 *     → Concentrated in 2 of 8 sub-segments
 *       → Trend: accelerating decline (3rd consecutive period down)
 */
@Component
public class InvestigationTreeBuilder {

    private static final int MAX_TREE_DEPTH    = 3;
    private static final int MAX_SEGMENTS      = 5;
    private static final double HIGH_IMPACT_PCT = 60.0;

    /**
     * Build investigation trees for all significant comparisons in the evidence.
     */
    public List<InvestigationNode> build(
            List<ComparativeContext> comparisons,
            List<QueryResult>        queryResults
    ) {
        List<InvestigationNode> roots = new ArrayList<>();

        for (ComparativeContext ctx : comparisons) {
            // Only investigate material movements
            if (Math.abs(ctx.deltaPercent()) < 5.0) continue;

            InvestigationNode root = buildRoot(ctx, queryResults);
            if (root != null) roots.add(root);
        }

        return roots;
    }

    // ─── root node: the primary signal ───────────────────────────────────

    private InvestigationNode buildRoot(ComparativeContext ctx, List<QueryResult> results) {
        String signal = formatSignal(ctx);
        String interpretation = interpretSignal(ctx);

        List<InvestigationNode> children = buildSegmentNodes(ctx, results, 1);

        return new InvestigationNode(
                signal,
                ctx.comparisonType().name(),
                ctx.metricKey(),
                ctx.directionLabel(),
                Math.abs(ctx.deltaPercent()),
                interpretation,
                children
        );
    }

    // ─── level 2: segment breakdown ──────────────────────────────────────

    private List<InvestigationNode> buildSegmentNodes(
            ComparativeContext ctx, List<QueryResult> results, int depth
    ) {
        if (depth >= MAX_TREE_DEPTH) return List.of();

        // Find a ranking query for the same entity that can explain segment contribution
        Optional<QueryResult> rankingResult = results.stream()
                .filter(r -> r.key().startsWith("ranking__") && !r.rows().isEmpty())
                .findFirst();

        if (rankingResult.isEmpty()) return List.of();

        QueryResult ranking = rankingResult.get();
        double totalMetricValue = ranking.rows().stream()
                .mapToDouble(row -> numericValue(detectMetricValue(row)))
                .sum();

        if (totalMetricValue == 0) return List.of();

        List<InvestigationNode> segments = new ArrayList<>();
        String dimColumn = detectDimensionColumn(ranking.rows().get(0));

        ranking.rows().stream().limit(MAX_SEGMENTS).forEach(row -> {
            Object dimVal    = row.get(dimColumn);
            double segVal    = numericValue(detectMetricValue(row));
            double sharePct  = (segVal / totalMetricValue) * 100.0;
            String segSignal = String.format("%s accounts for %.1f%% of total",
                    dimVal, sharePct);

            String segInterp = sharePct > HIGH_IMPACT_PCT
                    ? "HIGH CONCENTRATION — this single segment dominates the signal"
                    : sharePct > 30
                    ? "SIGNIFICANT — material contributor"
                    : "MINOR contributor";

            List<InvestigationNode> grandchildren =
                    buildConcentrationNode(dimVal, segVal, totalMetricValue, ranking.rows(), depth + 1);

            segments.add(new InvestigationNode(
                    segSignal, dimColumn,
                    dimColumn, dimVal != null ? dimVal.toString() : "unknown",
                    sharePct, segInterp, grandchildren
            ));
        });

        return segments;
    }

    // ─── level 3: concentration analysis ─────────────────────────────────

    private List<InvestigationNode> buildConcentrationNode(
            Object topSegment, double topSegVal, double total,
            List<Map<String, Object>> rows, int depth
    ) {
        if (depth >= MAX_TREE_DEPTH || rows.size() < 2) return List.of();

        long entitiesAboveAvg = rows.stream()
                .filter(r -> numericValue(detectMetricValue(r)) > (total / rows.size()))
                .count();

        double concentrationPct = (topSegVal / total) * 100.0;
        String interpretation;

        if (concentrationPct > 50) {
            interpretation = String.format(
                    "Highly concentrated: top segment alone holds %.0f%% of total. " +
                    "Only %d of %d entities above average — impact is narrow, not systemic.",
                    concentrationPct, entitiesAboveAvg, rows.size());
        } else {
            interpretation = String.format(
                    "Broadly distributed: %d of %d entities above average. " +
                    "Signal is systemic across multiple segments.",
                    entitiesAboveAvg, rows.size());
        }

        return List.of(new InvestigationNode(
                String.format("Concentration: %.0f%% in top segment", concentrationPct),
                "concentration",
                "entities_above_avg", String.valueOf(entitiesAboveAvg),
                concentrationPct, interpretation, List.of()
        ));
    }

    // ─── interpretation ───────────────────────────────────────────────────

    private String formatSignal(ComparativeContext ctx) {
        String dir    = ctx.directionLabel().toUpperCase();
        String pctStr = String.format("%.1f%%", Math.abs(ctx.deltaPercent()));
        String metric = shortMetricName(ctx.metricKey());
        return switch (ctx.comparisonType()) {
            case PERIOD_OVER_PERIOD  -> metric + " " + dir + " " + pctStr + " vs prior period";
            case YEAR_OVER_YEAR      -> metric + " " + dir + " " + pctStr + " YoY";
            case VS_BASELINE         -> metric + " is " + pctStr + " " + dir.toLowerCase() + " vs historical baseline";
            case VS_PEER             -> "Leader is " + pctStr + " ahead of closest peer";
            case VS_COHORT_AVERAGE   -> "Top entity is " + pctStr + " " + dir.toLowerCase() + " vs cohort average";
            default                  -> metric + " deviation: " + pctStr;
        };
    }

    private String interpretSignal(ComparativeContext ctx) {
        double absPct = Math.abs(ctx.deltaPercent());
        String severity = absPct > 30 ? "CRITICAL" : absPct > 15 ? "SIGNIFICANT" : "NOTABLE";

        return switch (ctx.directionLabel()) {
            case "up"   -> severity + " upward movement. Investigate whether this is structural growth or transient spike.";
            case "down" -> severity + " downward movement. Requires root-cause investigation and corrective review.";
            default     -> "Stable. No material directional movement detected.";
        };
    }

    // ─── helpers ──────────────────────────────────────────────────────────

    private Object detectMetricValue(Map<String, Object> row) {
        for (String k : List.of("metric_value", "total", "value", "amount", "count", "sum")) {
            if (row.containsKey(k)) return row.get(k);
        }
        return row.values().stream().filter(v -> isNumeric(v)).findFirst().orElse(null);
    }

    private String detectDimensionColumn(Map<String, Object> row) {
        return row.keySet().stream()
                .filter(k -> !List.of("metric_value", "total", "value", "amount",
                                      "count", "sum", "avg", "average").contains(k))
                .findFirst()
                .orElse("dimension");
    }

    private String shortMetricName(String key) {
        String[] parts = key.split("\\.");
        return parts.length > 0 ? parts[parts.length - 1].replace("_", " ") : key;
    }

    private boolean isNumeric(Object v) {
        if (v == null) return false;
        if (v instanceof Number) return true;
        try { Double.parseDouble(v.toString()); return true; }
        catch (NumberFormatException e) { return false; }
    }

    private double numericValue(Object v) {
        if (v == null) return 0;
        if (v instanceof Number n) return n.doubleValue();
        try { return Double.parseDouble(v.toString()); }
        catch (NumberFormatException e) { return 0; }
    }
}
