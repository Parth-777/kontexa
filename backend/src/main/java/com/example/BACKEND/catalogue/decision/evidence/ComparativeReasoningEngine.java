package com.example.BACKEND.catalogue.decision.evidence;

import com.example.BACKEND.catalogue.decision.contracts.DecisionModels.*;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * Derives comparative contexts from computed query results.
 *
 * Rather than firing additional warehouse queries (which adds latency),
 * this engine extracts comparisons from what is already in the
 * {@link ComputationResultSet}:
 *
 *   PERIOD_OVER_PERIOD — consecutive rows in time-series results
 *   VS_PEER            — ranks the entity against sibling entities in a ranking result
 *   VS_BASELINE        — compares current value to statistical baseline (mean of series)
 *   VS_COHORT_AVERAGE  — compares top entity to the cohort average of the group
 *
 * Comparisons are attached to EvidenceObjects, not sent as raw rows to the LLM.
 */
@Component
public class ComparativeReasoningEngine {

    private static final double FLAT_THRESHOLD_PCT = 1.0;

    /**
     * Produce all comparative contexts derivable from one entity's query results.
     */
    public List<ComparativeContext> derive(String entityRef, List<QueryResult> queryResults) {
        List<ComparativeContext> contexts = new ArrayList<>();

        for (QueryResult qr : queryResults) {
            if (qr.rows().isEmpty()) continue;

            String key = qr.key();

            if (isTimeSeries(key)) {
                contexts.addAll(periodOverPeriod(entityRef, key, qr.rows()));
                contexts.addAll(vsBaseline(entityRef, key, qr.rows()));
            }

            if (isRanking(key)) {
                contexts.addAll(vsPeerAndCohort(entityRef, key, qr.rows()));
            }
        }

        return contexts;
    }

    // ─── period-over-period ──────────────────────────────────────────────

    private List<ComparativeContext> periodOverPeriod(
            String entityRef, String queryKey, List<Map<String, Object>> rows) {

        if (rows.size() < 2) return List.of();

        List<ComparativeContext> out = new ArrayList<>();
        String metricKey = detectMetricColumn(rows.get(0));
        if (metricKey == null) return List.of();

        // Assume rows are ordered newest-first (ORDER BY period DESC in TrendMetricPack)
        Map<String, Object> current  = rows.get(0);
        Map<String, Object> previous = rows.get(1);

        double cur  = numericValue(current.get(metricKey));
        double prev = numericValue(previous.get(metricKey));
        if (prev == 0 && cur == 0) return List.of();

        double delta = cur - prev;
        double pct   = prev != 0 ? (delta / Math.abs(prev)) * 100.0 : 0.0;

        out.add(new ComparativeContext(
                entityRef, queryKey + "." + metricKey,
                cur, prev, delta, pct,
                ComparisonType.PERIOD_OVER_PERIOD,
                periodLabel(current), periodLabel(previous),
                direction(pct)
        ));

        // Year-over-year if we have >= 13 rows
        if (rows.size() >= 13) {
            Map<String, Object> yoy = rows.get(12);
            double yoyVal = numericValue(yoy.get(metricKey));
            double yoyDelta = cur - yoyVal;
            double yoyPct   = yoyVal != 0 ? (yoyDelta / Math.abs(yoyVal)) * 100.0 : 0.0;
            out.add(new ComparativeContext(
                    entityRef, queryKey + "." + metricKey,
                    cur, yoyVal, yoyDelta, yoyPct,
                    ComparisonType.YEAR_OVER_YEAR,
                    periodLabel(current), periodLabel(yoy),
                    direction(yoyPct)
            ));
        }

        return out;
    }

    // ─── vs statistical baseline (mean of series) ────────────────────────

    private List<ComparativeContext> vsBaseline(
            String entityRef, String queryKey, List<Map<String, Object>> rows) {

        if (rows.size() < 3) return List.of();
        String metricKey = detectMetricColumn(rows.get(0));
        if (metricKey == null) return List.of();

        double current = numericValue(rows.get(0).get(metricKey));
        double baseline = rows.stream()
                .skip(1)
                .mapToDouble(r -> numericValue(r.get(metricKey)))
                .average()
                .orElse(0);

        if (baseline == 0) return List.of();

        double delta = current - baseline;
        double pct   = (delta / Math.abs(baseline)) * 100.0;

        return List.of(new ComparativeContext(
                entityRef, queryKey + "." + metricKey,
                current, baseline, delta, pct,
                ComparisonType.VS_BASELINE,
                "current", "historical_avg",
                direction(pct)
        ));
    }

    // ─── vs peer / cohort average ─────────────────────────────────────────

    private List<ComparativeContext> vsPeerAndCohort(
            String entityRef, String queryKey, List<Map<String, Object>> rows) {

        if (rows.size() < 2) return List.of();
        String metricKey = detectMetricColumn(rows.get(0));
        if (metricKey == null) return List.of();

        double top     = numericValue(rows.get(0).get(metricKey));
        double second  = numericValue(rows.get(1).get(metricKey));
        double cohortAvg = rows.stream()
                .mapToDouble(r -> numericValue(r.get(metricKey)))
                .average().orElse(0);

        List<ComparativeContext> out = new ArrayList<>();

        // Top vs second peer
        if (second > 0) {
            double delta = top - second;
            double pct   = (delta / Math.abs(second)) * 100.0;
            out.add(new ComparativeContext(
                    entityRef, queryKey + "." + metricKey,
                    top, second, delta, pct,
                    ComparisonType.VS_PEER,
                    "rank_1", "rank_2",
                    direction(pct)
            ));
        }

        // Top vs cohort average
        if (cohortAvg > 0) {
            double delta = top - cohortAvg;
            double pct   = (delta / Math.abs(cohortAvg)) * 100.0;
            out.add(new ComparativeContext(
                    entityRef, queryKey + "." + metricKey,
                    top, cohortAvg, delta, pct,
                    ComparisonType.VS_COHORT_AVERAGE,
                    "top_entity", "cohort_average",
                    direction(pct)
            ));
        }

        return out;
    }

    // ─── helpers ─────────────────────────────────────────────────────────

    private boolean isTimeSeries(String key) { return key.startsWith("trend__"); }
    private boolean isRanking(String key)    { return key.startsWith("ranking__"); }

    /** Heuristically finds the numeric metric column in a row. */
    private String detectMetricColumn(Map<String, Object> row) {
        // Prefer columns named "metric_value", "total", "value", "amount"
        for (String pref : List.of("metric_value", "total", "value", "amount", "revenue",
                                   "count", "sum", "avg", "average")) {
            if (row.containsKey(pref) && isNumeric(row.get(pref))) return pref;
        }
        // Fallback: first numeric column
        return row.entrySet().stream()
                .filter(e -> isNumeric(e.getValue()))
                .map(Map.Entry::getKey)
                .findFirst()
                .orElse(null);
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

    private String periodLabel(Map<String, Object> row) {
        for (String col : List.of("period", "month", "date", "week", "year")) {
            Object v = row.get(col);
            if (v != null) return v.toString();
        }
        return "unknown";
    }

    private String direction(double pct) {
        if (pct > FLAT_THRESHOLD_PCT)  return "up";
        if (pct < -FLAT_THRESHOLD_PCT) return "down";
        return "flat";
    }
}
