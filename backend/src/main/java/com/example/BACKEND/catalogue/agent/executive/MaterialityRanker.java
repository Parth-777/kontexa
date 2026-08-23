package com.example.BACKEND.catalogue.agent.executive;

import com.example.BACKEND.catalogue.agent.DecisionMemoryService;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Ranks insight candidates by business-relative materiality (not raw unit magnitude).
 */
@Component
public class MaterialityRanker {

    public static final int MIN_INSIGHT_CARDS = 3;
    private static final int TOP_N = 5;
    private static final Pattern PCT = Pattern.compile("(\\d+(?:\\.\\d+)?)%");

    private final DecisionMemoryService decisionMemoryService;

    public MaterialityRanker(DecisionMemoryService decisionMemoryService) {
        this.decisionMemoryService = decisionMemoryService;
    }

    public List<InsightCandidate> selectTop(
            List<InsightCandidate> candidates,
            String clientId,
            int baseConfidence) {

        if (candidates == null || candidates.isEmpty()) return List.of();

        List<InsightCandidate> ranked = candidates.stream()
                .map(c -> scoreWithMemory(c, clientId, baseConfidence))
                .sorted(Comparator.comparingDouble(InsightCandidate::impactScore).reversed())
                .collect(Collectors.toList());

        return selectDiverse(ranked, TOP_N);
    }

    private List<InsightCandidate> selectDiverse(List<InsightCandidate> ranked, int topN) {
        List<InsightCandidate> picked = new ArrayList<>();
        Set<String> usedTopics = new HashSet<>();

        // Prefer distinct topics first (never fill min with duplicates when alternatives exist)
        for (InsightCandidate c : ranked) {
            if (picked.size() >= topN) break;
            String topic = topicKey(c);
            if (!usedTopics.contains(topic)) {
                picked.add(c);
                usedTopics.add(topic);
            }
        }

        if (picked.size() < topN) {
            for (InsightCandidate c : ranked) {
                if (picked.size() >= topN) break;
                if (!picked.contains(c)) picked.add(c);
            }
        }
        return picked;
    }

    private String topicKey(InsightCandidate c) {
        if (c.sourceColumns() != null && !c.sourceColumns().isEmpty()) {
            return c.sourceColumns().get(0).toLowerCase(Locale.ROOT);
        }
        if (c.evidenceRefs() != null && !c.evidenceRefs().isEmpty()) {
            String ref = c.evidenceRefs().get(0).toLowerCase(Locale.ROOT);
            if (ref.contains(" by ")) {
                int idx = ref.lastIndexOf(" by ");
                return ref.substring(idx + 4).trim();
            }
            if (ref.contains("concentration")) {
                int sp = ref.lastIndexOf(' ');
                if (sp > 0) return ref.substring(sp + 1);
            }
            return ref;
        }
        if (c.driverSegment() != null && !c.driverSegment().isBlank()) {
            return c.lens().name() + ":" + c.driverSegment().toLowerCase(Locale.ROOT);
        }
        return c.lens().name() + ":" + (c.claim() != null ? c.claim().length() : 0);
    }

    private InsightCandidate scoreWithMemory(InsightCandidate c, String clientId, int baseConfidence) {
        // Bound incoming lens scores — treat as relative signal, not raw magnitude
        double relative = Math.min(50, Math.max(0, c.impactScore()));
        // Prefer share/% language when present in the claim
        relative = Math.max(relative, extractShareBoost(c.claim()));

        double score = relative;
        score += badgeWeight(c.badge());
        score += impactWeight(c.impactLevel());
        // Small, equal-ish lens prior — do not privilege revenue magnitude
        if (c.lens() == InsightLens.GENERAL) score += 4;
        if (c.lens() == InsightLens.REVENUE) score += 4;
        if (c.lens() == InsightLens.GROWTH) score += 4;
        if (c.lens() == InsightLens.RISK) score += 5;

        int adjusted = decisionMemoryService.adjustConfidence(
                clientId, c.badge(), c.lens().agentLabel(), baseConfidence);
        if (adjusted > baseConfidence) score += 5;
        if (adjusted < baseConfidence - 10) score -= 5;

        return new InsightCandidate(
                c.claim(), c.lens(), c.tableName(), score, c.badge(), c.impactLevel(),
                c.evidenceRefs(), c.metricHighlights(), c.sourceColumns(),
                c.suggestedOwner(), c.driverSegment());
    }

    private double extractShareBoost(String claim) {
        if (claim == null) return 0;
        Matcher m = PCT.matcher(claim);
        double best = 0;
        while (m.find()) {
            try {
                best = Math.max(best, Double.parseDouble(m.group(1)));
            } catch (NumberFormatException ignored) {
                // skip
            }
        }
        return Math.min(45, best);
    }

    private double badgeWeight(String badge) {
        if (badge == null) return 0;
        return switch (badge) {
            case "ALERT" -> 20;
            case "RISK" -> 15;
            case "OPPORTUNITY" -> 12;
            default -> 3;
        };
    }

    private double impactWeight(String level) {
        if (level == null) return 0;
        return switch (level) {
            case "HIGH" -> 15;
            case "MEDIUM" -> 8;
            case "POSITIVE" -> 6;
            default -> 2;
        };
    }
}
