package com.example.BACKEND.catalogue.agent;

import com.example.BACKEND.catalogue.entity.InsightCardEntity;
import com.example.BACKEND.catalogue.repository.InsightCardRepository;
import com.example.BACKEND.identity.auth.AuthContext;
import com.example.BACKEND.identity.auth.AuthContextHolder;
import com.example.BACKEND.tenancy.TenantContext;
import com.example.BACKEND.tenancy.TenantContextHolder;
import com.example.BACKEND.tenancy.TenantRole;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Insights V2 lifecycle + tenant isolation.
 * Enable: -Dkontexa.live.insights.inbox=true
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@EnabledIfSystemProperty(named = "kontexa.live.insights.inbox", matches = "true")
class InsightInboxLifecycleIntegrationTest {

    @Autowired AgentOrchestrator orchestrator;
    @Autowired InsightCardRepository insightCardRepository;
    @Autowired JdbcTemplate jdbc;

    @AfterEach
    void clear() {
        AuthContextHolder.clear();
        TenantContextHolder.clear();
    }

    @Test
    @Transactional
    @DisplayName("read/unread/dismiss + cross-tenant denied")
    void lifecycleAndIsolation() {
        try {
            jdbc.execute("ALTER TABLE insight_cards ADD COLUMN IF NOT EXISTS read_at TIMESTAMP NULL");
        } catch (Exception ignored) {
            // already applied
        }

        String tenantA = "netflix-admin";
        String tenantB = "oil";
        UUID wsA = UUID.randomUUID();

        InsightCardEntity card = new InsightCardEntity();
        card.setClientId(tenantA);
        card.setTitle("Revenue dropped 3.1% in Q2");
        card.setDescription("Enterprise churn drove most of the decline.");
        card.setImpactLevel("HIGH");
        card.setBadge("ALERT");
        card.setAgentName("Growth");
        card.setConfidence(85);
        card.setMetricHighlights("[{\"label\":\"QoQ\",\"value\":\"-3.1%\"}]");
        card.setReasons("[\"Enterprise ARR fell\"]");
        card.setStrategies("[]");
        card.setStatus("AWAITING_CONFIRMATION");
        card.setGeneratedAt(LocalDateTime.now());
        card.setExpiresAt(LocalDateTime.now().plusDays(7));
        card = insightCardRepository.save(card);
        UUID id = card.getId();
        assertNotNull(id);

        bindAuth(wsA, tenantA);

        List<InsightCardEntity> inbox = orchestrator.getActiveInsights(tenantA);
        assertTrue(inbox.stream().anyMatch(c -> id.equals(c.getId())));
        assertTrue(inbox.stream().filter(c -> id.equals(c.getId())).findFirst().orElseThrow().isUnread());

        assertTrue(orchestrator.updateInsightStatus(id, tenantA, "COMPLETED"));
        InsightCardEntity afterRead = insightCardRepository.findById(id).orElseThrow();
        assertEquals("COMPLETED", afterRead.getStatus());
        assertNotNull(afterRead.getReadAt());
        assertFalse(afterRead.isUnread());

        // Still listed after read
        assertTrue(orchestrator.getActiveInsights(tenantA).stream().anyMatch(c -> id.equals(c.getId())));

        assertTrue(orchestrator.updateInsightStatus(id, tenantA, "AWAITING_CONFIRMATION"));
        InsightCardEntity afterUnread = insightCardRepository.findById(id).orElseThrow();
        assertEquals("AWAITING_CONFIRMATION", afterUnread.getStatus());
        assertNull(afterUnread.getReadAt());

        // Cross-tenant update denied
        assertFalse(orchestrator.updateInsightStatus(id, tenantB, "DECLINED"));
        assertEquals("AWAITING_CONFIRMATION",
                insightCardRepository.findById(id).orElseThrow().getStatus());

        assertTrue(orchestrator.updateInsightStatus(id, tenantA, "DECLINED"));
        assertTrue(orchestrator.getActiveInsights(tenantA).stream().noneMatch(c -> id.equals(c.getId())));
    }

    private void bindAuth(UUID workspaceId, String slug) {
        TenantContextHolder.set(new TenantContext(
                workspaceId, slug, UUID.randomUUID(), "insights-test@local",
                "Insights Test", TenantRole.ADMIN, UUID.randomUUID(), "test"));
        AuthContextHolder.set(new AuthContext(
                UUID.randomUUID(), UUID.randomUUID(), workspaceId, slug,
                "insights-test@local", "Insights Test", "ADMIN", "test"));
    }
}
