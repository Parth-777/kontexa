package com.example.BACKEND.catalogue.decision.execution.framework;

import com.example.BACKEND.catalogue.decision.analytics.RowAnalytics;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Discovers the structural and statistical profile of a result set's schema.
 *
 * Classification is PURELY structural — it uses:
 *   1. Statistical properties: magnitude, cardinality, integer dominance, range
 *   2. Generic semantic tokens: terms that apply across all business domains
 *      (e.g. "count", "total", "rate" — NOT domain-specific like "fare" or "revenue")
 *   3. Distribution shape: right-skewed high-magnitude → VALUE; bounded → RATE
 *
 * NO domain-specific column names, business concepts, or dataset assumptions.
 *
 * Classification rules (in priority order):
 *
 *   TIME_BUCKET — column name contains: hour, day, week, month, year, quarter,
 *                 period, date, time, ts; OR column contains date-like strings
 *
 *   RATE        — numeric AND bounded [0, 1] (95th pct ≤ 1.0); OR name contains
 *                 rate, ratio, pct, percent, fraction, share, proportion
 *
 *   VOLUME      — numeric AND integer-dominant AND name contains:
 *                 count, cnt, qty, quantity, num, total, sum when paired with integer signal;
 *                 OR mean < 10,000 AND integer-dominant AND not high-cardinality
 *
 *   VALUE       — numeric AND high-magnitude (mean > 1) AND right-skewed;
 *                 OR name contains: total, sum, amount, value, cost, price,
 *                 spend, income, output, revenue, metric (generic)
 *
 *   IDENTIFIER  — string AND cardinality ≥ 90% of row count (primary key / UUID)
 *
 *   DIMENSION   — string AND cardinality < 50% of row count; OR numeric AND
 *                 cardinality ≤ 30 distinct values (categorical numeric)
 */
@Component
public class SchemaProfiler {

    private static final double DIMENSION_CARDINALITY_THRESHOLD = 0.15;
    private static final double IDENTIFIER_CARDINALITY_THRESHOLD = 0.90;
    private static final double INTEGER_DOMINANCE_THRESHOLD = 0.80;
    private static final double RATE_UPPER_BOUND = 1.05; // allow slight floating point margin

    // Generic time tokens — domain-agnostic
    private static final Set<String> TIME_TOKENS = Set.of(
            "hour", "day", "week", "month", "year", "quarter", "period",
            "date", "time", "ts", "timestamp", "created", "updated", "at"
    );

    // Generic rate tokens — domain-agnostic
    private static final Set<String> RATE_TOKENS = Set.of(
            "rate", "ratio", "pct", "percent", "percentage", "fraction",
            "share", "proportion", "index", "score", "probability"
    );

    // Generic volume tokens — domain-agnostic
    private static final Set<String> VOLUME_TOKENS = Set.of(
            "count", "cnt", "qty", "quantity", "num", "n_", "_n",
            "total_count", "events", "records", "orders", "units", "items", "ops"
    );

    // Generic value/magnitude tokens — domain-agnostic
    private static final Set<String> VALUE_TOKENS = Set.of(
            "total", "sum", "amount", "value", "cost", "price", "revenue", "profit",
            "spend", "income", "output", "metric", "measure", "kpi", "margin"
    );

    // Grouping-key tokens for pre-aggregated warehouse results
    private static final Set<String> ENTITY_GROUP_TOKENS = Set.of(
            "entity", "dimension", "group", "category", "label", "segment",
            "field", "region", "zone", "bucket", "facility", "type", "name"
    );

    // ─── public API ──────────────────────────────────────────────────────

    public SchemaProfile profile(List<Map<String, Object>> rows) {
        if (rows == null || rows.isEmpty())
            return new SchemaProfile(List.of(), 0);

        int n = rows.size();
        Set<String> cols = rows.get(0).keySet();

        List<ColumnProfile> profiles = cols.stream()
                .map(col -> profileColumn(col, rows, n))
                .filter(Objects::nonNull)
                .collect(Collectors.toList());

        profiles = reclassifyGroupedEntityColumns(profiles);

        return new SchemaProfile(profiles, n);
    }

    // ─── column classification ───────────────────────────────────────────

    private ColumnProfile profileColumn(String col, List<Map<String, Object>> rows, int n) {
        String lower = col.toLowerCase();

        // Collect sample values
        List<Object> rawValues = rows.stream()
                .map(r -> r.get(col))
                .filter(Objects::nonNull)
                .collect(Collectors.toList());

        if (rawValues.isEmpty()) return null;

        // Numeric analysis
        List<Double> numVals = rawValues.stream()
                .map(RowAnalytics::toDouble)
                .filter(v -> !Double.isNaN(v))
                .collect(Collectors.toList());

        boolean isNumeric = numVals.size() >= rawValues.size() * 0.5;

        // Cardinality
        long distinct = rawValues.stream().map(Object::toString)
                .map(String::toLowerCase)
                .distinct().count();
        int cardinality = (int) distinct;
        double cardRatio = (double) cardinality / n;

        // Numeric stats
        double mean   = isNumeric ? RowAnalytics.mean(numVals) : 0;
        double max    = isNumeric ? RowAnalytics.max(numVals) : 0;
        double min    = isNumeric ? RowAnalytics.min(numVals) : 0;
        double stdDev = isNumeric ? RowAnalytics.stdDev(numVals) : 0;

        boolean intDominant = isNumeric && isIntegerDominant(numVals);

        // ── Classification ────────────────────────────────────────────────

        // 1. TIME_BUCKET
        if (containsAny(lower, TIME_TOKENS)) {
            return new ColumnProfile(col, ColumnRole.TIME_BUCKET,
                    mean, max, min, stdDev, cardinality, n, isNumeric, intDominant, cardRatio);
        }

        if (!isNumeric) {
            // Entity grouping keys in pre-aggregated results (entity, oil_field, region, …)
            if (containsAny(lower, ENTITY_GROUP_TOKENS)) {
                return new ColumnProfile(col, ColumnRole.DIMENSION,
                        0, 0, 0, 0, cardinality, n, false, false, cardRatio);
            }
            // 2. IDENTIFIER — high-cardinality string
            if (cardRatio >= IDENTIFIER_CARDINALITY_THRESHOLD) {
                return new ColumnProfile(col, ColumnRole.IDENTIFIER,
                        0, 0, 0, 0, cardinality, n, false, false, cardRatio);
            }
            // 3. DIMENSION — low-cardinality string
            if (cardRatio < DIMENSION_CARDINALITY_THRESHOLD || cardinality <= 50) {
                return new ColumnProfile(col, ColumnRole.DIMENSION,
                        0, 0, 0, 0, cardinality, n, false, false, cardRatio);
            }
            return new ColumnProfile(col, ColumnRole.UNKNOWN,
                    0, 0, 0, 0, cardinality, n, false, false, cardRatio);
        }

        // Numeric path

        // 4. RATE — bounded [0, 1] OR rate-like name
        if (containsAny(lower, RATE_TOKENS)) {
            return new ColumnProfile(col, ColumnRole.RATE,
                    mean, max, min, stdDev, cardinality, n, true, intDominant, cardRatio);
        }
        if (max <= RATE_UPPER_BOUND && min >= 0 && mean < 1.0) {
            return new ColumnProfile(col, ColumnRole.RATE,
                    mean, max, min, stdDev, cardinality, n, true, intDominant, cardRatio);
        }

        // 5. Categorical numeric — very few distinct values (e.g. status code 0/1)
        if (cardinality <= 20 && intDominant) {
            return new ColumnProfile(col, ColumnRole.DIMENSION,
                    mean, max, min, stdDev, cardinality, n, true, true, cardRatio);
        }

        // 6. VOLUME — integer-dominant + volume semantic name
        if (intDominant && containsAny(lower, VOLUME_TOKENS)) {
            return new ColumnProfile(col, ColumnRole.VOLUME,
                    mean, max, min, stdDev, cardinality, n, true, true, cardRatio);
        }

        // 7. VALUE — value semantic name OR high-magnitude non-integer
        if (containsAny(lower, VALUE_TOKENS) || (!intDominant && mean > 1.0)) {
            return new ColumnProfile(col, ColumnRole.VALUE,
                    mean, max, min, stdDev, cardinality, n, true, intDominant, cardRatio);
        }

        // 8. VOLUME — integer-dominant without explicit name match (count-like)
        if (intDominant && mean < 100_000) {
            return new ColumnProfile(col, ColumnRole.VOLUME,
                    mean, max, min, stdDev, cardinality, n, true, true, cardRatio);
        }

        // 9. VALUE — high-magnitude numeric as fallback
        if (mean > 1.0) {
            return new ColumnProfile(col, ColumnRole.VALUE,
                    mean, max, min, stdDev, cardinality, n, true, intDominant, cardRatio);
        }

        return new ColumnProfile(col, ColumnRole.UNKNOWN,
                mean, max, min, stdDev, cardinality, n, isNumeric, intDominant, cardRatio);
    }

    private List<ColumnProfile> reclassifyGroupedEntityColumns(List<ColumnProfile> profiles) {
        boolean hasValue = profiles.stream().anyMatch(p -> p.role() == ColumnRole.VALUE);
        if (!hasValue) return profiles;

        long stringCols = profiles.stream().filter(p -> !p.isNumeric()).count();
        if (stringCols != 1) return profiles;

        List<ColumnProfile> out = new ArrayList<>();
        for (ColumnProfile p : profiles) {
            if (p.role() == ColumnRole.IDENTIFIER && !p.isNumeric()) {
                out.add(new ColumnProfile(
                        p.columnName(), ColumnRole.DIMENSION,
                        p.mean(), p.max(), p.min(), p.stdDev(),
                        p.cardinality(), p.rowCount(), p.isNumeric(),
                        p.isIntegerDominant(), p.cardinalityRatio()));
            } else {
                out.add(p);
            }
        }
        return out;
    }

    // ─── helpers ─────────────────────────────────────────────────────────

    private boolean isIntegerDominant(List<Double> vals) {
        if (vals.isEmpty()) return false;
        long intCount = vals.stream()
                .filter(v -> v == Math.floor(v) && !Double.isInfinite(v))
                .count();
        return (double) intCount / vals.size() >= INTEGER_DOMINANCE_THRESHOLD;
    }

    private boolean containsAny(String lower, Set<String> tokens) {
        for (String t : tokens) {
            if (lower.contains(t)) return true;
        }
        return false;
    }
}
