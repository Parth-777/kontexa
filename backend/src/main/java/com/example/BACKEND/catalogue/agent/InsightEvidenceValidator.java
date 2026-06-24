package com.example.BACKEND.catalogue.agent;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Programmatic claim verification before persist (plan Pillar 4).
 * Checks numbers, category labels, and trend language against collected evidence.
 */
@Component
public class InsightEvidenceValidator {

    private static final Pattern NUMBER = Pattern.compile("\\d+(?:\\.\\d+)?%?");
    private static final Pattern TREND_WORDS = Pattern.compile(
            "\\b(rose|declined|increased|decreased|trend|growing|shrinking|accelerat|decelerat)\\b",
            Pattern.CASE_INSENSITIVE);

    public List<AgentDashboardResult.InsightCard> filterSupported(
            List<AgentDashboardResult.InsightCard> cards,
            List<CollectedData> collected) {
        if (cards == null || cards.isEmpty()) return cards;

        String corpus = buildCorpus(collected).toLowerCase();
        Set<String> labels = extractCategoryLabels(collected);
        boolean hasTimeSeries = hasMultiPeriodSeries(collected);

        List<AgentDashboardResult.InsightCard> kept = new ArrayList<>();

        for (AgentDashboardResult.InsightCard card : cards) {
            if (isExempt(card)) {
                kept.add(card);
                continue;
            }
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
            kept.add(card);
        }
        return kept;
    }

    private boolean isExempt(AgentDashboardResult.InsightCard card) {
        String agent = card.getAgentName() != null ? card.getAgentName().toLowerCase() : "";
        return agent.contains("root cause") || agent.contains("forecast")
                || agent.contains("general discovery") || agent.contains("revenue model")
                || agent.contains("growth") || agent.contains("risk")
                || agent.contains("efficiency") || agent.contains("customer");
    }

    private boolean numbersSupported(AgentDashboardResult.InsightCard card, String corpus) {
        List<String> numbers = new ArrayList<>();
        extractNumbers(card.getTitle(), numbers);
        extractNumbers(card.getDescription(), numbers);
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
        String bare = num.replace("%", "").trim();
        if (corpus.contains(num.toLowerCase()) || corpus.contains(bare.toLowerCase())) return true;
        try {
            double d = Double.parseDouble(bare);
            String[] variants = {
                    String.format("%.0f", d),
                    String.format("%.1f", d),
                    String.format("%.2f", d),
                    String.format("%.0f%%", d),
                    String.format("%.1f%%", d)
            };
            for (String v : variants) {
                if (corpus.contains(v.toLowerCase())) return true;
            }
        } catch (NumberFormatException ignored) {
            // non-numeric highlight text
        }
        return false;
    }

    private boolean labelsSupported(AgentDashboardResult.InsightCard card, Set<String> labels) {
        if (labels.isEmpty()) return true;

        Set<String> allowed = new HashSet<>(labels);
        if (card.getMetricHighlights() != null) {
            for (AgentDashboardResult.MetricHighlight h : card.getMetricHighlights()) {
                if (h.getValue() != null && !h.getValue().isBlank()) {
                    allowed.add(h.getValue().toLowerCase());
                }
            }
        }

        String text = (card.getTitle() + " " + card.getDescription()).toLowerCase();

        List<String> suspicious = new ArrayList<>();
        for (String word : text.split("[\\s,—–-]+")) {
            if (word.length() < 4) continue;
            if (!allowed.contains(word.toLowerCase())) {
                boolean looksLikeCategory = word.matches("[A-Za-z]+");
                if (looksLikeCategory && !isCommonWord(word)) {
                    suspicious.add(word);
                }
            }
        }

        return suspicious.size() <= 2;
    }

    private boolean trendSupported(AgentDashboardResult.InsightCard card, boolean hasTimeSeries) {
        String combined = (card.getTitle() + " " + String.join(" ",
                card.getReasons() != null ? card.getReasons() : List.of())).toLowerCase();
        Matcher m = TREND_WORDS.matcher(combined);
        if (!m.find()) return true;
        return hasTimeSeries;
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
                if (seg != null && !seg.toString().isBlank()) {
                    labels.add(seg.toString().toLowerCase());
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
                    || lbl.startsWith("KPI:") || lbl.startsWith("DISCOVERY:")
                    || lbl.startsWith("REVENUE:") || lbl.equals("Volume over time"))) {
                if (cd.rows() != null && cd.rows().size() >= 2) return true;
            }
        }
        return false;
    }

    private boolean isCommonWord(String w) {
        return Set.of("Revenue", "Orders", "Sales", "Growth", "Risk", "High", "Store", "Total")
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
