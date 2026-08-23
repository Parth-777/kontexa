package com.example.BACKEND.catalogue.agent;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Fail-closed claim verification for Insights cards (pre- and post-polish).
 */
@Component
public class InsightEvidenceValidator {

    private static final Pattern NUMBER = Pattern.compile(
            "\\$?\\d+(?:\\.\\d+)?(?:[BMKbmK])?%?");
    private static final Pattern PCT = Pattern.compile("(\\d+(?:\\.\\d+)?)\\s*%");
    private static final Pattern MONEY = Pattern.compile(
            "\\$\\s*\\d+(?:\\.\\d+)?\\s*[BMKbmK]?", Pattern.CASE_INSENSITIVE);
    private static final Pattern TREND_WORDS = Pattern.compile(
            "\\b(rose|declined|increased|decreased|trend|growing|shrinking|accelerat|decelerat|fell)\\b",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern OF_TOTAL = Pattern.compile(
            "\\bof\\s+total\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern CONCENTRATION = Pattern.compile(
            "\\b(concentration|dominat|100\\s*%|all\\s+revenue)\\b", Pattern.CASE_INSENSITIVE);

    public List<AgentDashboardResult.InsightCard> filterSupported(
            List<AgentDashboardResult.InsightCard> cards,
            List<CollectedData> collected) {
        if (cards == null || cards.isEmpty()) return cards;

        String corpus = buildCorpus(collected).toLowerCase(Locale.ROOT);
        Set<String> labels = extractCategoryLabels(collected);
        boolean hasTimeSeries = hasMultiPeriodSeries(collected);
        boolean hasGrandTotalShare = hasGrandTotalShareEvidence(collected);
        boolean hasCurrencyScale = hasCurrencyScaleEvidence(collected);

        List<AgentDashboardResult.InsightCard> kept = new ArrayList<>();

        for (AgentDashboardResult.InsightCard card : cards) {
            if (isExempt(card)) {
                kept.add(card);
                continue;
            }
            String text = cardText(card);
            if (!numbersSupported(card, corpus)) {
                System.out.printf("[EvidenceValidator] Dropped (numbers): %s%n", card.getTitle());
                continue;
            }
            if (!labelsSupported(card, labels)) {
                System.out.printf("[EvidenceValidator] Dropped (labels): %s%n", card.getTitle());
                continue;
            }
            if (!trendSupported(card, hasTimeSeries)) {
                System.out.printf("[EvidenceValidator] Dropped (trend): %s%n", card.getTitle());
                continue;
            }
            if (!percentOfTotalSupported(text, corpus, hasGrandTotalShare)) {
                System.out.printf("[EvidenceValidator] Dropped (%% of total): %s%n", card.getTitle());
                continue;
            }
            if (!currencySupported(text, hasCurrencyScale)) {
                System.out.printf("[EvidenceValidator] Dropped (currency unit): %s%n", card.getTitle());
                continue;
            }
            if (!concentrationSupported(text, collected)) {
                System.out.printf("[EvidenceValidator] Dropped (concentration): %s%n", card.getTitle());
                continue;
            }
            kept.add(card);
        }
        return kept;
    }

    private boolean isExempt(AgentDashboardResult.InsightCard card) {
        String agent = card.getAgentName() != null ? card.getAgentName().toLowerCase(Locale.ROOT) : "";
        return agent.contains("root cause") || agent.contains("forecast");
    }

    private String cardText(AgentDashboardResult.InsightCard card) {
        StringBuilder sb = new StringBuilder();
        if (card.getTitle() != null) sb.append(card.getTitle()).append(' ');
        if (card.getDescription() != null) sb.append(card.getDescription()).append(' ');
        if (card.getReasons() != null) {
            for (String r : card.getReasons()) sb.append(r).append(' ');
        }
        if (card.getMetricHighlights() != null) {
            for (AgentDashboardResult.MetricHighlight h : card.getMetricHighlights()) {
                if (h.getValue() != null) sb.append(h.getValue()).append(' ');
            }
        }
        return sb.toString();
    }

    private boolean numbersSupported(AgentDashboardResult.InsightCard card, String corpus) {
        List<String> numbers = new ArrayList<>();
        extractNumbers(card.getTitle(), numbers);
        extractNumbers(card.getDescription(), numbers);
        if (card.getReasons() != null) {
            for (String r : card.getReasons()) extractNumbers(r, numbers);
        }
        if (card.getMetricHighlights() != null) {
            for (AgentDashboardResult.MetricHighlight h : card.getMetricHighlights()) {
                extractNumbers(h.getValue(), numbers);
            }
        }
        if (numbers.isEmpty()) return true;

        int matched = 0;
        for (String num : numbers) {
            if (numberInCorpus(num, corpus)) matched++;
        }
        return matched >= Math.ceil(numbers.size() * 0.5);
    }

    private boolean numberInCorpus(String num, String corpus) {
        if (num == null || num.isBlank()) return true;
        String bare = num.replace("$", "").replace("%", "").trim();
        if (corpus.contains(num.toLowerCase(Locale.ROOT)) || corpus.contains(bare.toLowerCase(Locale.ROOT))) {
            return true;
        }
        try {
            // Strip compact suffixes for matching (6.12B → 6.12)
            String core = bare.replaceAll("[BMKbmK]$", "");
            double d = Double.parseDouble(core);
            String[] variants = {
                    String.format(Locale.US, "%.0f", d),
                    String.format(Locale.US, "%.1f", d),
                    String.format(Locale.US, "%.2f", d),
                    String.format(Locale.US, "%.0f%%", d),
                    String.format(Locale.US, "%.1f%%", d),
                    String.format(Locale.US, "%.1f", d) + "%",
            };
            for (String v : variants) {
                if (corpus.contains(v.toLowerCase(Locale.ROOT))) return true;
            }
        } catch (NumberFormatException ignored) {
            // non-numeric
        }
        return false;
    }

    private boolean labelsSupported(AgentDashboardResult.InsightCard card, Set<String> labels) {
        if (labels.isEmpty()) return true;

        Set<String> allowed = new HashSet<>(labels);
        if (card.getMetricHighlights() != null) {
            for (AgentDashboardResult.MetricHighlight h : card.getMetricHighlights()) {
                if (h.getValue() != null && !h.getValue().isBlank()) {
                    allowed.add(h.getValue().toLowerCase(Locale.ROOT));
                }
            }
        }

        String text = (card.getTitle() + " " + card.getDescription()).toLowerCase(Locale.ROOT);

        // Allow tokens from source columns / humanized dimension paths in the claim
        if (card.getSourceColumns() != null) {
            for (String col : card.getSourceColumns()) {
                if (col == null) continue;
                for (String part : col.toLowerCase(Locale.ROOT).split("[._\\s]+")) {
                    if (part.length() >= 3) allowed.add(part);
                }
            }
        }

        List<String> suspicious = new ArrayList<>();
        for (String word : text.split("[\\s,—–-]+")) {
            if (word.length() < 4) continue;
            if (!allowed.contains(word.toLowerCase(Locale.ROOT))) {
                boolean looksLikeCategory = word.matches("[A-Za-z]+");
                if (looksLikeCategory && !isCommonWord(word)) {
                    suspicious.add(word);
                }
            }
        }
        return suspicious.size() <= 3;
    }

    private boolean trendSupported(AgentDashboardResult.InsightCard card, boolean hasTimeSeries) {
        String combined = cardText(card).toLowerCase(Locale.ROOT);
        Matcher m = TREND_WORDS.matcher(combined);
        if (!m.find()) return true;
        return hasTimeSeries;
    }

    private boolean percentOfTotalSupported(String text, String corpus, boolean hasGrandTotalShare) {
        if (!OF_TOTAL.matcher(text).find()) return true;
        if (!hasGrandTotalShare) return false;
        Matcher m = PCT.matcher(text);
        while (m.find()) {
            String pct = m.group(1);
            // Must appear in evidence (share_of_total_pct values)
            if (!corpus.contains(pct) && !corpus.contains(pct + "%")
                    && !corpus.contains(String.format(Locale.US, "%.1f", Double.parseDouble(pct)))) {
                return false;
            }
        }
        return true;
    }

    private boolean currencySupported(String text, boolean hasCurrencyScale) {
        Matcher m = MONEY.matcher(text);
        if (!m.find()) return true;
        return hasCurrencyScale;
    }

    private boolean concentrationSupported(String text, List<CollectedData> collected) {
        if (!CONCENTRATION.matcher(text).find()) return true;
        if (collected == null) return false;
        for (CollectedData cd : collected) {
            if (cd.rows() == null) continue;
            for (Map<String, Object> row : cd.rows()) {
                if (row.containsKey("share_of_total_pct") && row.containsKey("grand_total")) {
                    Object sc = row.get("segment_count");
                    int count = 0;
                    if (sc instanceof Number n) count = n.intValue();
                    if (count >= 2) return true;
                    Object share = row.get("share_of_total_pct");
                    if (share != null && row.get("grand_total") != null) return true;
                }
                if (row.containsKey("top_share_pct") && row.containsKey("grand_total")) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean hasGrandTotalShareEvidence(List<CollectedData> collected) {
        if (collected == null) return false;
        for (CollectedData cd : collected) {
            if (cd.rows() == null) continue;
            for (Map<String, Object> row : cd.rows()) {
                if (row.containsKey("share_of_total_pct") && row.containsKey("grand_total")) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean hasCurrencyScaleEvidence(List<CollectedData> collected) {
        if (collected == null) return false;
        for (CollectedData cd : collected) {
            if (cd.rows() == null) continue;
            for (Map<String, Object> row : cd.rows()) {
                Object unit = row.get("metric_display_unit");
                Object scale = row.get("metric_scale_to_canonical");
                if (unit != null && scale != null) {
                    String u = unit.toString().toLowerCase(Locale.ROOT);
                    if (u.contains("currency") || u.equals("usd") || u.equals("money")) {
                        try {
                            double s = Double.parseDouble(scale.toString());
                            if (s != 0 && !Double.isNaN(s)) return true;
                        } catch (Exception ignored) {
                            // continue
                        }
                    }
                }
            }
        }
        return false;
    }

    private Set<String> extractCategoryLabels(List<CollectedData> collected) {
        Set<String> labels = new HashSet<>();
        if (collected == null) return labels;

        for (CollectedData cd : collected) {
            String lbl = cd.label();
            if (lbl == null) continue;
            if (!lbl.startsWith("Distribution:") && !lbl.contains("contribution")
                    && !lbl.contains("breakdown") && !lbl.startsWith("EXEC:")
                    && !lbl.startsWith("DISCOVERY:") && !lbl.startsWith("REVENUE:")) {
                continue;
            }
            for (Map<String, Object> row : cd.rows()) {
                Object seg = row.get("segment");
                if (seg == null) seg = row.get("dimension_value");
                if (seg == null) seg = row.get("factor");
                if (seg == null) seg = row.get("top_segment");
                if (seg == null) seg = row.get("strongest_factor");
                if (seg != null && !seg.toString().isBlank()) {
                    labels.add(seg.toString().toLowerCase(Locale.ROOT));
                }
            }
        }
        return labels;
    }

    private boolean hasMultiPeriodSeries(List<CollectedData> collected) {
        if (collected == null) return false;
        for (CollectedData cd : collected) {
            String lbl = cd.label();
            if (lbl != null && (lbl.startsWith("Trend:") || lbl.contains("MoM")
                    || lbl.contains("period-over-period") || lbl.contains("monthly trend")
                    || lbl.startsWith("KPI:") || lbl.equals("Volume over time"))) {
                if (cd.rows() != null && cd.rows().size() >= 2) return true;
            }
        }
        return false;
    }

    private boolean isCommonWord(String w) {
        return Set.of("Revenue", "Orders", "Sales", "Growth", "Risk", "High", "Store", "Total",
                        "Source", "Market", "Base", "Value", "Location", "Country", "Geonetwork",
                        "Largest", "Underperforming", "Window", "Across", "Holds", "Mix",
                        "Sessions", "Records", "Primary", "Segment", "Dominance", "Concentration")
                .contains(w);
    }

    private void extractNumbers(String text, List<String> out) {
        if (text == null || text.isBlank()) return;
        Matcher m = NUMBER.matcher(text);
        while (m.find()) out.add(m.group());
    }

    private String buildCorpus(List<CollectedData> collected) {
        StringBuilder sb = new StringBuilder();
        if (collected == null) return "";
        for (CollectedData cd : collected) {
            sb.append(cd.label()).append(" ");
            if (cd.rows() == null) continue;
            for (Map<String, Object> row : cd.rows()) {
                for (Object v : row.values()) {
                    if (v != null) sb.append(v.toString()).append(" ");
                }
            }
        }
        return sb.toString();
    }
}
