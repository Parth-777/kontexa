package com.example.BACKEND.catalogue.agent;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Fail-closed quality gate before insight persistence.
 * Drops cards that lack evidence-backed substance or only carry generic consulting advice.
 * Does not invent findings.
 */
@Component
public class InsightQualityGate {

    private static final Pattern HAS_NUMBER = Pattern.compile("\\d");
    private static final Pattern GENERIC_ADVICE = Pattern.compile(
            "(?i)(enhance\\s+data|upgrade\\s+analytics|improve\\s+analytics|"
                    + "monitor\\s+performance|optimize\\s+strategy|consider\\s+optimiz|"
                    + "data\\s+may\\s+require|align\\s+cross-functional|"
                    + "owner-assigned\\s+response|implement\\s+cost\\s+control|"
                    + "review\\s+operational|improve\\s+data\\s+integration|"
                    + "platform\\s+capabilities|next\\s+month\\b|"
                    + "analytics\\s+capabilities|data\\s+synthesis\\s+processes)");

    /** Technical/operational failures must never surface as business findings. */
    private static final Pattern OPERATIONAL_FAILURE = Pattern.compile(
            "(?i)(insight\\s+synthesis\\s+error|synthesis\\s+error|synthesis\\s+failed|"
                    + "llm\\s+synthesis|insight\\s+synthesis\\s+encountered|"
                    + "raw\\s+kpi\\s+values\\s+are\\s+available|"
                    + "failure\\s+to\\s+synthesize|"
                    + "data\\s+was\\s+collected\\s+but\\s+insight\\s+synthesis)");

    public record GateResult(
            List<AgentDashboardResult.InsightCard> kept,
            int rejectedCount,
            List<String> rejectionReasons
    ) {}

    public GateResult filter(List<AgentDashboardResult.InsightCard> cards) {
        if (cards == null || cards.isEmpty()) {
            return new GateResult(List.of(), 0, List.of());
        }
        List<AgentDashboardResult.InsightCard> kept = new ArrayList<>();
        List<String> reasons = new ArrayList<>();
        int rejected = 0;
        for (AgentDashboardResult.InsightCard card : cards) {
            String reason = rejectReason(card);
            if (reason != null) {
                rejected++;
                reasons.add((card.getTitle() == null ? "(untitled)" : card.getTitle()) + ": " + reason);
                System.out.printf("[InsightQualityGate] Dropped: %s — %s%n",
                        card.getTitle(), reason);
                continue;
            }
            sanitizeStrategies(card);
            kept.add(card);
        }
        return new GateResult(List.copyOf(kept), rejected, List.copyOf(reasons));
    }

    private String rejectReason(AgentDashboardResult.InsightCard card) {
        if (card == null) {
            return "null card";
        }
        String title = safe(card.getTitle());
        String desc = safe(card.getDescription());
        if (title.isBlank()) {
            return "missing title";
        }
        if (isOperationalFailure(card, title, desc)) {
            return "operational/system failure must not become a business insight";
        }
        if (!hasEvidenceSignal(card, title, desc)) {
            return "insufficient evidence signal (no metrics/numbers)";
        }
        if (isGenericFiller(title, desc)) {
            return "generic filler text without grounded finding";
        }
        return null;
    }

    private boolean isOperationalFailure(
            AgentDashboardResult.InsightCard card, String title, String desc) {
        StringBuilder blob = new StringBuilder(title).append(' ').append(desc);
        if (card.getReasons() != null) {
            for (String r : card.getReasons()) {
                if (r != null) blob.append(' ').append(r);
            }
        }
        return OPERATIONAL_FAILURE.matcher(blob.toString()).find();
    }

    private boolean hasEvidenceSignal(
            AgentDashboardResult.InsightCard card, String title, String desc) {
        if (card.getMetricHighlights() != null && !card.getMetricHighlights().isEmpty()) {
            return true;
        }
        if (HAS_NUMBER.matcher(title).find() || HAS_NUMBER.matcher(desc).find()) {
            return true;
        }
        if (card.getReasons() != null) {
            for (String r : card.getReasons()) {
                if (r != null && HAS_NUMBER.matcher(r).find()) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean isGenericFiller(String title, String desc) {
        String combined = title + " " + desc;
        if (GENERIC_ADVICE.matcher(combined).find() && !HAS_NUMBER.matcher(combined).find()) {
            return true;
        }
        String lower = combined.toLowerCase(Locale.ROOT);
        return (lower.contains("improve analytics") || lower.contains("enhance data")
                || lower.contains("upgrade analytics"))
                && !HAS_NUMBER.matcher(combined).find();
    }

    private void sanitizeStrategies(AgentDashboardResult.InsightCard card) {
        if (card.getStrategies() == null || card.getStrategies().isEmpty()) {
            return;
        }
        List<String> kept = new ArrayList<>();
        for (String s : card.getStrategies()) {
            if (s == null || s.isBlank()) continue;
            String lower = s.toLowerCase(Locale.ROOT);
            if (GENERIC_ADVICE.matcher(s).find() && !HAS_NUMBER.matcher(s).find()) {
                continue;
            }
            if (lower.contains("align cross-functional") || lower.contains("monitor closely")
                    || lower.contains("investigate further") || lower.contains("consider analyzing")) {
                continue;
            }
            kept.add(s.trim());
        }
        card.setStrategies(kept);
    }

    /**
     * True when title/description/reasons look like a technical failure dressed as a finding.
     * Used to hide historically persisted poison cards from the inbox.
     */
    public boolean isOperationalFailureText(String title, String description, String reasonsJson) {
        String blob = safe(title) + " " + safe(description) + " " + safe(reasonsJson);
        return OPERATIONAL_FAILURE.matcher(blob).find();
    }

    private static String safe(String s) {
        return s == null ? "" : s.trim();
    }
}
