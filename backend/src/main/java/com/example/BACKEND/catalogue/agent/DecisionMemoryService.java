package com.example.BACKEND.catalogue.agent;

import com.example.BACKEND.catalogue.entity.DecisionMemoryEntity;
import com.example.BACKEND.catalogue.entity.InsightCardEntity;
import com.example.BACKEND.catalogue.repository.DecisionMemoryRepository;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Set;

/**
 * Learns from user actions on insight cards to tune future insight confidence.
 *
 *   COMPLETED  — user marked as read (insight was useful / understood)
 *   DECLINED   — user dismissed (insight was irrelevant)
 *
 * Acceptance rate per badge and per agent name adjusts confidence on new cards:
 *   high read rate  → boost similar insights
 *   high dismiss rate → reduce similar insights
 */
@Service
public class DecisionMemoryService {

    private static final Set<String> VALID_ACTIONS  = Set.of("DECLINED", "COMPLETED");
    private static final int         MIN_HISTORY     = 5;
    private static final double      HIGH_ACCEPT     = 0.70;
    private static final double      LOW_ACCEPT      = 0.30;

    private final DecisionMemoryRepository repo;

    public DecisionMemoryService(DecisionMemoryRepository repo) {
        this.repo = repo;
    }

    /** Records mark-as-read (COMPLETED) or dismiss (DECLINED). */
    public void record(InsightCardEntity card, String action) {
        if (!VALID_ACTIONS.contains(action)) return;

        DecisionMemoryEntity memory = new DecisionMemoryEntity();
        memory.setClientId(card.getClientId());
        memory.setBadge(card.getBadge());
        memory.setAgentName(card.getAgentName());
        memory.setImpactLevel(card.getImpactLevel());
        memory.setAction(action);
        memory.setChangedAt(LocalDateTime.now());
        repo.save(memory);

        System.out.printf("[DecisionMemory] %s | badge=%s agent=%s action=%s%n",
                card.getClientId(), card.getBadge(), card.getAgentName(), action);
    }

    /**
     * Adjusts confidence using both badge-level and agent-level historical acceptance.
     */
    public int adjustConfidence(String clientId, String badge, String agentName, int baseConfidence) {
        double mult = 1.0;
        mult *= acceptanceMultiplier(clientId, badge, null);
        if (agentName != null && !agentName.isBlank()) {
            mult *= acceptanceMultiplier(clientId, null, agentName);
        }
        return Math.max(0, Math.min(100, (int) Math.round(baseConfidence * mult)));
    }

    private double acceptanceMultiplier(String clientId, String badge, String agentName) {
        long total;
        long completed;

        if (agentName != null && !agentName.isBlank()) {
            total     = repo.countTotalByAgent(clientId, agentName);
            completed = repo.countCompletedByAgent(clientId, agentName);
        } else if (badge != null && !badge.isBlank()) {
            total     = repo.countTotal(clientId, badge);
            completed = repo.countCompleted(clientId, badge);
        } else {
            return 1.0;
        }

        if (total < MIN_HISTORY) return 1.0;

        double acceptRate = (double) completed / total;
        if (acceptRate >= HIGH_ACCEPT) return 1.15;
        if (acceptRate <= LOW_ACCEPT)  return 0.80;
        return 1.0;
    }
}
