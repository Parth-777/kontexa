package com.example.BACKEND.catalogue.agent;

import com.example.BACKEND.catalogue.entity.InsightCardEntity;
import com.example.BACKEND.catalogue.repository.InsightCardRepository;
import com.example.BACKEND.catalogue.service.CatalogueApprovalService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;

/**
 * Read-only audit of catalogue snapshot metadata + persisted insight cards.
 * Enable: -Dkontexa.live.insights.audit=true
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@EnabledIfSystemProperty(named = "kontexa.live.insights.audit", matches = "true")
class InsightEvidenceCorrectnessAuditTest {

    @Autowired CatalogueApprovalService approvalService;
    @Autowired InsightCardRepository insightCardRepository;
    @Autowired ObjectMapper objectMapper;

    @Test
    @DisplayName("dump transactionRevenue catalogue semantics + active insight cards")
    void dumpRevenueFieldSemantics() throws Exception {
        String tenant = System.getProperty("kontexa.live.ga.tenant", "netflix-admin");

        System.out.println("========== REVENUE FIELD AUDIT ==========");
        System.out.println("TENANT: " + tenant);

        String snap = approvalService.getApprovedSnapshot(tenant);
        if (snap == null || snap.isBlank()) {
            System.out.println("SNAPSHOT: empty");
        } else {
            JsonNode root = objectMapper.readTree(snap);
            System.out.println("SNAPSHOT_TABLES: " + root.path("tables").size());
            System.out.println("SNAPSHOT_CATALOGUE_ID: " + root.path("catalogueId").asText("?"));
            System.out.println("SNAPSHOT_CLIENT_ID: " + root.path("clientId").asText("?"));
            int hits = 0;
            for (JsonNode table : root.path("tables")) {
                for (JsonNode col : table.path("columns")) {
                    String name = col.path("columnName").asText("");
                    String lower = name.toLowerCase();
                    if (!lower.contains("transactionrevenue")
                            && !lower.contains("totaltransactionrevenue")
                            && !lower.contains("revenue")) {
                        continue;
                    }
                    // Keep audit focused on revenue-like fields; print full detail for transaction*
                    boolean detail = lower.contains("transaction");
                    if (!detail && hits > 40) continue;
                    hits++;
                    System.out.println("--- SNAPSHOT COLUMN ---");
                    System.out.println("table: " + table.path("tableName").asText());
                    if (detail) {
                        System.out.println(col.toPrettyString());
                    } else {
                        System.out.println("columnName: " + name);
                        System.out.println("dataType: " + col.path("dataType").asText());
                        System.out.println("role: " + col.path("role").asText());
                        System.out.println("aggregationMethod: " + col.path("aggregationMethod").asText());
                        System.out.println("description: " + col.path("description").asText());
                        System.out.println("businessMeaning: " + col.path("businessMeaning").asText());
                        System.out.println("sampleValues: " + col.path("sampleValues"));
                        System.out.println("minValue: " + col.path("minValue").asText());
                        System.out.println("maxValue: " + col.path("maxValue").asText());
                        System.out.println("avgValue: " + col.path("avgValue").asText());
                    }
                }
            }
            System.out.println("REVENUE_LIKE_COLUMNS_PRINTED: " + hits);
        }

        System.out.println("========== ACTIVE INSIGHT CARDS ==========");
        List<InsightCardEntity> cards = insightCardRepository
                .findByClientIdAndStatusOrderByGeneratedAtDesc(tenant, "ACTIVE");
        System.out.println("ACTIVE_COUNT: " + cards.size());
        int i = 0;
        for (InsightCardEntity card : cards) {
            if (i >= 5) break;
            System.out.println("--- CARD[" + i + "] ---");
            System.out.println("title: " + card.getTitle());
            System.out.println("description: " + card.getDescription());
            System.out.println("badge: " + card.getBadge());
            System.out.println("impact: " + card.getImpactLevel());
            System.out.println("agent: " + card.getAgentName());
            System.out.println("metrics: " + card.getMetricHighlights());
            System.out.println("reasons: " + card.getReasons());
            System.out.println("sourceColumns: " + card.getSourceColumns());
            System.out.println("chartSpec: " + card.getChartSpec());
            String evidence = card.getRawEvidence();
            if (evidence != null && evidence.length() > 3000) {
                evidence = evidence.substring(0, 3000) + "...[truncated]";
            }
            System.out.println("rawEvidence: " + evidence);
            i++;
        }
        System.out.println("========== END REVENUE FIELD AUDIT ==========");
    }
}
