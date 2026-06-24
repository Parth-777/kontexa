package com.example.BACKEND.catalogue.decision.execution.materialization;

import org.springframework.stereotype.Component;

import java.time.*;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Detects timestamp columns by sampling raw values and adds derived temporal
 * dimensions (hour_of_day, weekday, month, quarter) as synthetic columns to a
 * working copy of the rows.
 *
 * KEY DIFFERENCE from SchemaProfiler's TIME_BUCKET detection:
 *   - SchemaProfiler uses column name heuristics (fast but misses unconventional names)
 *   - This class samples actual cell values to detect datetime content regardless of
 *     the column name.  Any column where ≥ 50% of sampled values parse successfully as
 *     a datetime is treated as a temporal source.
 *
 * Output: a new row list with the following synthetic keys added when possible:
 *   "{sourceColumn}_hour_of_day"   — e.g. "00", "01", … "23"  (zero-padded, sortable)
 *   "{sourceColumn}_weekday"       — MONDAY … SUNDAY
 *   "{sourceColumn}_month"         — JANUARY … DECEMBER
 *   "{sourceColumn}_quarter"       — Q1 … Q4
 *
 * No domain-specific column names are hardcoded.
 */
@Component
public class DerivedDimensionMaterializer {

    /** Fraction of sampled rows that must parse for the column to be treated as temporal. */
    private static final double PARSE_SUCCESS_THRESHOLD = 0.5;
    private static final int    SAMPLE_SIZE             = 20;

    /** Returns which columns have parseable datetime content (brute-force scan). */
    public List<String> detectTemporalColumns(List<Map<String, Object>> rows) {
        if (rows == null || rows.isEmpty()) return List.of();
        Set<String> candidates = rows.get(0).keySet();
        return candidates.stream()
                .filter(col -> !col.startsWith("_derived_"))  // skip already-derived
                .filter(col -> isTemporalColumn(col, rows))
                .collect(Collectors.toList());
    }

    /**
     * Adds derived temporal columns to every row (returns a new list; does not mutate input).
     */
    public List<Map<String, Object>> materializeTemporalDimensions(
            List<Map<String, Object>> rows,
            List<String> temporalColumns
    ) {
        if (temporalColumns.isEmpty()) return rows;

        return rows.stream()
                .map(row -> {
                    Map<String, Object> enriched = new LinkedHashMap<>(row);
                    for (String col : temporalColumns) {
                        LocalDateTime dt = parseDateTime(row.get(col));
                        if (dt == null) continue;
                        enriched.put("_derived_" + col + "_hour_of_day",
                                String.format("%02d:00", dt.getHour()));
                        enriched.put("_derived_" + col + "_weekday",
                                dt.getDayOfWeek().name());
                        enriched.put("_derived_" + col + "_month",
                                dt.getMonth().name());
                        enriched.put("_derived_" + col + "_quarter",
                                "Q" + ((dt.getMonthValue() - 1) / 3 + 1));
                    }
                    return enriched;
                })
                .collect(Collectors.toList());
    }

    // ─── detection ───────────────────────────────────────────────────────

    private boolean isTemporalColumn(String col, List<Map<String, Object>> rows) {
        int sampleSize  = Math.min(SAMPLE_SIZE, rows.size());
        long parsed = rows.subList(0, sampleSize).stream()
                .map(r -> r.get(col))
                .filter(Objects::nonNull)
                .filter(v -> parseDateTime(v) != null)
                .count();
        long total  = rows.subList(0, sampleSize).stream()
                .map(r -> r.get(col))
                .filter(Objects::nonNull)
                .count();
        return total > 0 && (double) parsed / total >= PARSE_SUCCESS_THRESHOLD;
    }

    // ─── parsing ──────────────────────────────────────────────────────────

    public LocalDateTime parseDateTime(Object raw) {
        if (raw == null) return null;

        if (raw instanceof LocalDateTime ldt) return ldt;
        if (raw instanceof OffsetDateTime odt) return odt.toLocalDateTime();
        if (raw instanceof ZonedDateTime  zdt) return zdt.toLocalDateTime();
        if (raw instanceof Instant        ins) return LocalDateTime.ofInstant(ins, ZoneOffset.UTC);
        if (raw instanceof java.sql.Timestamp ts) return ts.toLocalDateTime();
        if (raw instanceof java.util.Date   dt) return LocalDateTime.ofInstant(dt.toInstant(), ZoneOffset.UTC);
        if (raw instanceof LocalDate        ld) return ld.atStartOfDay();

        if (raw instanceof Number n) {
            long v = n.longValue();
            // epoch millis heuristic: value too large to be epoch seconds
            if (Math.abs(v) > 100_000_000_000L)
                return LocalDateTime.ofInstant(Instant.ofEpochMilli(v), ZoneOffset.UTC);
            if (Math.abs(v) > 1_000_000_000L)
                return LocalDateTime.ofInstant(Instant.ofEpochSecond(v), ZoneOffset.UTC);
            return null;
        }

        String s = raw.toString().trim();
        if (s.isBlank() || s.equalsIgnoreCase("null")) return null;

        // Try common datetime patterns, most specific first
        for (DateTimeFormatter fmt : FORMATTERS) {
            try { return LocalDateTime.parse(s, fmt); }
            catch (DateTimeParseException ignored) {}
        }
        // ISO instant (e.g. "2024-01-15T18:32:00Z")
        try { return LocalDateTime.ofInstant(Instant.parse(s), ZoneOffset.UTC); }
        catch (DateTimeParseException ignored) {}
        // Offset datetime (e.g. "2024-01-15T18:32:00+05:30")
        try { return OffsetDateTime.parse(s).toLocalDateTime(); }
        catch (DateTimeParseException ignored) {}
        // Date only (e.g. "2024-01-15")
        try { return LocalDate.parse(s, DateTimeFormatter.ISO_DATE).atStartOfDay(); }
        catch (DateTimeParseException ignored) {}

        return null;
    }

    private static final List<DateTimeFormatter> FORMATTERS = List.of(
            DateTimeFormatter.ISO_DATE_TIME,
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"),
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS"),
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"),
            DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss"),
            DateTimeFormatter.ofPattern("MM/dd/yyyy HH:mm:ss"),
            DateTimeFormatter.ofPattern("dd-MM-yyyy HH:mm:ss"),
            DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm:ss")
    );
}
