package com.example.BACKEND.catalogue.agent;

import com.example.BACKEND.catalogue.agent.executive.InsightCandidate;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Insights-only deterministic projection of warehouse evidence for LLM synthesis.
 * Never invents or LLM-summarizes numbers. Does not mutate the in-memory {@link CollectedData}
 * used for charts/persistence.
 */
public final class InsightsSynthesisPacketBuilder {

    /** Soft ceiling for synthesis user-prompt evidence body (chars). */
    public static final int MAX_EVIDENCE_CHARS = 48_000;
    public static final int MAX_DATASETS = 12;
    public static final int MAX_ROWS_PER_DATASET = 8;
    public static final int MAX_COLUMNS_PER_ROW = 12;
    public static final int MAX_CELL_CHARS = 80;
    public static final int MAX_SCHEMA_COLUMNS = 40;

    private InsightsSynthesisPacketBuilder() {}

    public record PacketStats(
            int datasetsIncluded,
            int rowsIncluded,
            int evidenceChars,
            boolean omittedSampleRows,
            boolean truncatedByBudget,
            Set<String> columnsReferenced
    ) {}

    public record Packet(String evidenceText, PacketStats stats) {}

    /**
     * Builds a bounded evidence block for insight synthesis/narration/polish.
     * @param relevantColumns optional allow-list from selected candidates (case-insensitive)
     */
    public static Packet build(
            List<CollectedData> collected,
            Collection<String> relevantColumns) {

        Set<String> allow = normalizeColumns(relevantColumns);
        StringBuilder sb = new StringBuilder();
        int datasets = 0;
        int rowsOut = 0;
        boolean omittedSample = false;
        boolean truncated = false;
        Set<String> seenCols = new LinkedHashSet<>();

        if (collected == null || collected.isEmpty()) {
            return new Packet("(no collected evidence)",
                    new PacketStats(0, 0, 0, false, false, Set.of()));
        }

        List<CollectedData> ordered = prioritize(collected);
        for (CollectedData cd : ordered) {
            if (datasets >= MAX_DATASETS) {
                truncated = true;
                break;
            }
            if (cd == null || cd.label() == null) continue;
            if (cd.label().startsWith("Sample rows")) {
                omittedSample = true;
                continue; // raw SELECT * dumps are never sent to synthesis
            }
            List<Map<String, Object>> rows = cd.rows();
            if (rows == null || rows.isEmpty()) continue;

            StringBuilder block = new StringBuilder();
            block.append("## ").append(cd.label()).append("\n");

            int show = Math.min(rows.size(), MAX_ROWS_PER_DATASET);
            List<String> keys = selectKeys(rows.get(0), allow);
            if (keys.isEmpty()) continue;

            seenCols.addAll(keys);
            block.append(String.join(" | ", keys)).append("\n");

            for (int i = 0; i < show; i++) {
                Map<String, Object> projected = projectRow(rows.get(i), keys);
                List<String> cells = new ArrayList<>(keys.size());
                for (String k : keys) {
                    cells.add(formatCell(projected.get(k)));
                }
                block.append(String.join(" | ", cells)).append("\n");
                rowsOut++;
            }
            if (rows.size() > show) {
                block.append("... (").append(rows.size() - show)
                        .append(" more rows omitted; aggregates above are complete for shown keys)\n");
            }
            block.append("\n");

            if (sb.length() + block.length() > MAX_EVIDENCE_CHARS) {
                truncated = true;
                break;
            }
            sb.append(block);
            datasets++;
        }

        if (sb.isEmpty()) {
            sb.append("(no synthesis-safe evidence datasets; raw sample rows omitted)\n");
        }

        return new Packet(sb.toString(), new PacketStats(
                datasets, rowsOut, sb.length(), omittedSample, truncated, Set.copyOf(seenCols)));
    }

    /** Schema lines only for columns that appear in evidence or candidate allow-list. */
    public static String schemaOverview(
            List<KpiDetectorService.ColumnHints> allHints,
            Set<String> columnsReferenced,
            Map<String, Map<String, String>> tableColMeanings) {

        StringBuilder sb = new StringBuilder();
        sb.append("SCHEMA (evidence-linked columns only)\n");
        sb.append("====================================\n");
        if (allHints == null || allHints.isEmpty()) {
            sb.append("(none)\n");
            return sb.toString();
        }
        int colCount = 0;
        for (KpiDetectorService.ColumnHints hints : allHints) {
            if (hints == null || hints.tableName() == null) continue;
            List<String> cols = new ArrayList<>();
            for (String c : hints.numericCols()) {
                if (includeColumn(c, columnsReferenced) && colCount < MAX_SCHEMA_COLUMNS) {
                    cols.add(c + meaningSuffix(tableColMeanings, hints.tableName(), c));
                    colCount++;
                }
            }
            for (String c : hints.stringCols()) {
                if (includeColumn(c, columnsReferenced) && colCount < MAX_SCHEMA_COLUMNS) {
                    cols.add(c);
                    colCount++;
                }
            }
            if (cols.isEmpty() && hints.dateCol() != null
                    && includeColumn(hints.dateCol(), columnsReferenced)) {
                cols.add(hints.dateCol() + " (date)");
                colCount++;
            }
            if (cols.isEmpty()) continue;
            sb.append("Table: ").append(hints.tableName()).append("\n");
            if (hints.dateCol() != null) {
                sb.append("  Date: ").append(hints.dateCol()).append("\n");
            }
            sb.append("  Columns: ").append(String.join(", ", cols)).append("\n");
        }
        if (colCount == 0) {
            sb.append("(no linked columns — candidates carry metric labels)\n");
        }
        return sb.toString();
    }

    public static Set<String> columnsFromCandidates(List<InsightCandidate> candidates) {
        Set<String> out = new LinkedHashSet<>();
        if (candidates == null) return out;
        for (InsightCandidate c : candidates) {
            if (c.sourceColumns() != null) {
                for (String col : c.sourceColumns()) {
                    if (col != null && !col.isBlank()) out.add(col.trim().toLowerCase(Locale.ROOT));
                }
            }
        }
        return out;
    }

    /** True if text appears to embed a full catalogue snapshot JSON. */
    public static boolean containsFullCatalogueDump(String prompt) {
        if (prompt == null) return false;
        String p = prompt.toLowerCase(Locale.ROOT);
        return p.contains("\"tables\"") && p.contains("\"columns\"")
                && p.contains("\"columnname\"") && p.contains("\"businessmeaning\"")
                && prompt.length() > 80_000;
    }

    private static List<CollectedData> prioritize(List<CollectedData> collected) {
        List<CollectedData> high = new ArrayList<>();
        List<CollectedData> mid = new ArrayList<>();
        List<CollectedData> low = new ArrayList<>();
        for (CollectedData cd : collected) {
            String label = cd.label() == null ? "" : cd.label();
            if (label.startsWith("Sample rows")) {
                low.add(cd); // skipped later
            } else if (label.startsWith("EXEC:") || label.startsWith("DISCOVERY:")
                    || label.startsWith("KPI:") || label.startsWith("Trend:")
                    || label.startsWith("SIGNALS:") || label.startsWith("ANOMALY")) {
                high.add(cd);
            } else {
                mid.add(cd);
            }
        }
        List<CollectedData> ordered = new ArrayList<>(high.size() + mid.size() + low.size());
        ordered.addAll(high);
        ordered.addAll(mid);
        ordered.addAll(low);
        return ordered;
    }

    private static List<String> selectKeys(Map<String, Object> row, Set<String> allow) {
        if (row == null || row.isEmpty()) return List.of();
        List<String> preferred = new ArrayList<>();
        List<String> other = new ArrayList<>();
        for (String key : row.keySet()) {
            if (key == null) continue;
            Object val = row.get(key);
            if (isHeavyNested(val)) continue; // drop STRUCT/ARRAY blobs from synthesis
            String lk = key.toLowerCase(Locale.ROOT);
            boolean allowed = allow.isEmpty() || allow.contains(lk)
                    || looksMetricKey(lk) || looksDimKey(lk);
            if (!allowed && !allow.isEmpty()) continue;
            if (looksMetricKey(lk) || looksDimKey(lk) || allow.contains(lk)) {
                preferred.add(key);
            } else {
                other.add(key);
            }
        }
        List<String> keys = new ArrayList<>();
        for (String k : preferred) {
            if (keys.size() >= MAX_COLUMNS_PER_ROW) break;
            keys.add(k);
        }
        for (String k : other) {
            if (keys.size() >= MAX_COLUMNS_PER_ROW) break;
            keys.add(k);
        }
        return keys;
    }

    private static Map<String, Object> projectRow(Map<String, Object> row, List<String> keys) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (String k : keys) {
            out.put(k, row.get(k));
        }
        return out;
    }

    private static String formatCell(Object v) {
        if (v == null) return "null";
        if (v instanceof Map<?, ?> || v instanceof Collection<?>) {
            return "[nested]";
        }
        String s = v.toString().replace('\n', ' ').trim();
        if (s.length() > MAX_CELL_CHARS) {
            return s.substring(0, MAX_CELL_CHARS - 3) + "...";
        }
        return s;
    }

    private static boolean isHeavyNested(Object v) {
        if (v instanceof Map<?, ?> m) return m.size() > 2;
        if (v instanceof Collection<?> c) return c.size() > 4;
        if (v == null) return false;
        String s = v.toString();
        return s.length() > 400 && (s.startsWith("{") || s.startsWith("["));
    }

    private static boolean looksMetricKey(String lk) {
        return lk.contains("revenue") || lk.contains("count") || lk.contains("total")
                || lk.contains("avg") || lk.contains("sum") || lk.contains("rate")
                || lk.contains("pct") || lk.contains("share") || lk.contains("sessions")
                || lk.contains("visits") || lk.contains("value") || lk.contains("amount")
                || lk.contains("change") || lk.contains("delta");
    }

    private static boolean looksDimKey(String lk) {
        return lk.contains("date") || lk.contains("segment") || lk.contains("channel")
                || lk.contains("source") || lk.contains("country") || lk.contains("device")
                || lk.contains("category") || lk.contains("name") || lk.contains("type")
                || lk.equals("metric") || lk.equals("dimension") || lk.contains("period");
    }

    private static Set<String> normalizeColumns(Collection<String> cols) {
        Set<String> out = new LinkedHashSet<>();
        if (cols == null) return out;
        for (String c : cols) {
            if (c != null && !c.isBlank()) out.add(c.trim().toLowerCase(Locale.ROOT));
        }
        return out;
    }

    private static boolean includeColumn(String col, Set<String> referenced) {
        if (col == null || col.isBlank()) return false;
        if (referenced == null || referenced.isEmpty()) return true;
        return referenced.contains(col.toLowerCase(Locale.ROOT));
    }

    private static String meaningSuffix(
            Map<String, Map<String, String>> tableColMeanings, String table, String col) {
        if (tableColMeanings == null) return "";
        Map<String, String> m = tableColMeanings.get(table);
        if (m == null) return "";
        String meaning = m.get(col.toLowerCase(Locale.ROOT));
        if (meaning == null || meaning.isBlank()) return "";
        String shortMeaning = meaning.length() > 40 ? meaning.substring(0, 37) + "..." : meaning;
        return " (" + shortMeaning + ")";
    }
}
