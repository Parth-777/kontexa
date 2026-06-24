package com.example.BACKEND.catalogue.agent.executive;

import com.example.BACKEND.catalogue.agent.AgentDashboardResult;

import java.util.Locale;

/**
 * Shared executive tone, metric labeling, and number formatting for C-suite copy.
 */
public final class ExecutiveVoice {

    public static final String PERSONA = """
            You are the Senior VP of Analytics briefing the President/CEO of a global institution \
            (board-level, MSCI-caliber audience).
            
            VOICE:
            - Decisive, concise, confident — never tentative or academic
            - Business English only: never expose database column names (no snake_case)
            - Lead with business impact ($, %, share, margin, volume) — not methodology
            - Translate technical metrics into plain labels (e.g. domestic_sales_barrel → Domestic barrel sales)
            
            BANNED:
            - "The data shows", "anomaly:", "metric_value", "in window", numbered raw dumps
            - Unformatted long decimals or scientific notation (use $59.5M, 245%, 7.7 pts)
            - Vague advice: monitor, investigate further, consider analyzing
            
            REQUIRED:
            - Past tense for what happened; imperative for what leadership should do next
            - Round numbers for readability; keep precision only when material
            """;

    private ExecutiveVoice() {}

    /** e.g. domestic_sales_barrel → Domestic Sales (Barrel) */
    public static String humanizeMetric(String raw) {
        if (raw == null || raw.isBlank()) return "Key metric";
        String s = raw.trim().replace('.', ' ').replace('-', ' ');
        String[] parts = s.split("_");
        StringBuilder sb = new StringBuilder();
        for (String p : parts) {
            if (p.isBlank()) continue;
            if (!sb.isEmpty()) sb.append(' ');
            sb.append(capitalizeWord(p));
        }
        return sb.isEmpty() ? raw : sb.toString();
    }

    public static String anomalyHeadline(AgentDashboardResult.Anomaly a) {
        if (a == null) return "Material shift detected";
        String name = humanizeMetric(a.getMetric());
        double pct = Math.abs(a.getChangePercent());
        String verb = "DOWN".equalsIgnoreCase(a.getDirection()) ? "declined" : "increased";
        return name + " " + verb + " " + formatPercent(pct) + " vs recent baseline";
    }

    public static String formatPercent(double v) {
        double abs = Math.abs(v);
        if (abs >= 100) return String.format(Locale.US, "%.0f%%", abs);
        return String.format(Locale.US, "%.1f%%", abs);
    }

    /** Compact value for highlights and briefs. */
    public static String formatValue(Object v) {
        if (v == null) return "";
        double n;
        if (v instanceof Number num) {
            n = num.doubleValue();
        } else {
            try {
                n = Double.parseDouble(v.toString());
            } catch (Exception e) {
                return v.toString();
            }
        }
        double abs = Math.abs(n);
        if (abs >= 1_000_000_000) return String.format(Locale.US, "$%.2fB", n / 1_000_000_000);
        if (abs >= 1_000_000) return String.format(Locale.US, "$%.1fM", n / 1_000_000);
        if (abs >= 10_000) return String.format(Locale.US, "$%.0fK", n / 1_000);
        if (abs >= 100) return String.format(Locale.US, "%.0f", n);
        if (abs >= 1) return String.format(Locale.US, "%.1f", n);
        return String.format(Locale.US, "%.2f", n);
    }

    private static String capitalizeWord(String w) {
        if (w.length() <= 3 && !w.equals("oil")) {
            String upper = w.toUpperCase(Locale.ROOT);
            if (upper.equals("KPI") || upper.equals("MOM") || upper.equals("YOY") || upper.equals("API")) {
                return upper;
            }
        }
        return w.substring(0, 1).toUpperCase(Locale.ROOT) + w.substring(1).toLowerCase(Locale.ROOT);
    }
}
