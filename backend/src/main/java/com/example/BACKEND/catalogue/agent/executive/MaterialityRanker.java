package com.example.BACKEND.catalogue.agent.executive;

import com.example.BACKEND.catalogue.agent.DecisionMemoryService;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Ranks insight candidates by materiality; keeps top N for executive inbox.
 */
@Component
public class MaterialityRanker {

    /** Minimum insight cards shown per refresh (executive inbox). */
    public static final int MIN_INSIGHT_CARDS = 3;
    private static final int TOP_N = 5;

    private final DecisionMemoryService decisionMemoryService;

    public MaterialityRanker(DecisionMemoryService decisionMemoryService) {
        this.decisionMemoryService = decisionMemoryService;
    }

    public List<InsightCandidate> selectTop(
            List<InsightCandidate> candidates,
            String clientId,
            int baseConfidence) {

        if (candidates == null || candidates.isEmpty()) return List.of();

        int topN = TOP_N;
        boolean hasHighAlert = candidates.stream()
                .anyMatch(c -> "ALERT".equals(c.badge()) && "HIGH".equals(c.impactLevel()));
        if (hasHighAlert && candidates.size() > MIN_INSIGHT_CARDS) {
            topN = TOP_N;
        }

        List<InsightCandidate> ranked = candidates.stream()
                .map(c -> scoreWithMemory(c, clientId, baseConfidence))
                .sorted(Comparator.comparingDouble(InsightCandidate::impactScore).reversed())
                .collect(Collectors.toList());

        return selectDiverse(ranked, topN);
    }

    /**
     * Prefer insights from different columns/topics — avoid three cards on the same flag column.
     */
    private List<InsightCandidate> selectDiverse(List<InsightCandidate> ranked, int topN) {
        List<InsightCandidate> picked = new ArrayList<>();
        Set<String> usedTopics = new HashSet<>();

        for (InsightCandidate c : ranked) {
            if (picked.size() >= topN) break;
            String topic = topicKey(c);
            if (picked.size() < MIN_INSIGHT_CARDS || !usedTopics.contains(topic)) {
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
            return c.sourceColumns().get(0).toLowerCase();
        }
        if (c.evidenceRefs() != null && !c.evidenceRefs().isEmpty()) {
            String ref = c.evidenceRefs().get(0).toLowerCase();
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
            return c.lens().name() + ":" + c.driverSegment().toLowerCase();
        }
        return c.lens().name() + ":" + (c.claim() != null ? c.claim().length() : 0);
    }

    private InsightCandidate scoreWithMemory(InsightCandidate c, String clientId, int baseConfidence) {
        double score = c.impactScore();
        score += badgeWeight(c.badge());
        score += impactWeight(c.impactLevel());
        if (c.lens() == InsightLens.GENERAL) score += 8;
        if (c.lens() == InsightLens.REVENUE) score += 12;

        int adjusted = decisionMemoryService.adjustConfidence(
                clientId, c.badge(), c.lens().agentLabel(), baseConfidence);
        if (adjusted > baseConfidence) score += 5;
        if (adjusted < baseConfidence - 10) score -= 5;

        return new InsightCandidate(
                c.claim(), c.lens(), c.tableName(), score, c.badge(), c.impactLevel(),
                c.evidenceRefs(), c.metricHighlights(), c.sourceColumns(),
                c.suggestedOwner(), c.driverSegment());
    }

    private double badgeWeight(String badge) {
        if (badge == null) return 0;
        return switch (badge) {
            case "ALERT" -> 25;
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
