package com.example.BACKEND.catalogue.agent;

import com.example.BACKEND.catalogue.agent.AgentDashboardResult.InsightCard;
import com.example.BACKEND.catalogue.agent.AgentDashboardResult.MetricHighlight;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InsightQualityGateTest {

    private final InsightQualityGate gate = new InsightQualityGate();

    @Test
    @DisplayName("keeps evidence-backed insight with numbers and metrics")
    void keepsEvidenceBacked() {
        InsightCard card = new InsightCard(
                "Revenue dropped 12.4% WoW",
                "Enterprise segment drove most of the decline.",
                "HIGH");
        card.setMetricHighlights(List.of(new MetricHighlight("WoW change", "-12.4%")));
        card.setReasons(List.of("Enterprise ARR fell 18%"));
        card.setStrategies(List.of("Sales — renegotiate top 3 accounts — +8% price — 60 days"));

        var result = gate.filter(List.of(card));
        assertEquals(1, result.kept().size());
        assertEquals(0, result.rejectedCount());
    }

    @Test
    @DisplayName("drops generic filler without numbers")
    void dropsGenericFiller() {
        InsightCard card = new InsightCard(
                "Enhance data integration processes",
                "Upgrade analytics platform capabilities next month.",
                "MEDIUM");
        var result = gate.filter(List.of(card));
        assertEquals(0, result.kept().size());
        assertEquals(1, result.rejectedCount());
    }

    @Test
    @DisplayName("strips generic strategies but keeps card when title has evidence")
    void stripsGenericStrategies() {
        InsightCard card = new InsightCard(
                "Paid Search bounce rate increased 18%",
                "Other acquisition channels remained stable.",
                "HIGH");
        card.setMetricHighlights(List.of(new MetricHighlight("bounce", "+18%")));
        card.setStrategies(List.of(
                "Align cross-functional response within 30 days.",
                "Marketing — pause underperforming campaigns — cut CPA 15% — this quarter"));

        var result = gate.filter(List.of(card));
        assertEquals(1, result.kept().size());
        assertEquals(1, result.kept().getFirst().getStrategies().size());
        assertTrue(result.kept().getFirst().getStrategies().getFirst().contains("Marketing"));
    }

    @Test
    @DisplayName("failed/empty evidence path yields no fake insights")
    void emptyListPassthrough() {
        var result = gate.filter(List.of());
        assertTrue(result.kept().isEmpty());
        assertEquals(0, result.rejectedCount());
    }

    @Test
    @DisplayName("synthesis/API failure card is rejected even if it invents a dollar figure")
    void dropsOperationalFailureMasqueradingAsInsight() {
        InsightCard card = new InsightCard(
                "Insight synthesis error — missed revenue opportunities",
                "The failure to synthesize insights resulted in an estimated $1.2M in potential revenue loss.",
                "MEDIUM");
        card.setBadge("ALERT");
        card.setReasons(List.of(
                "Synthesis error occurred during analysis phase, impacting decision-making."));

        var result = gate.filter(List.of(card));
        assertEquals(0, result.kept().size());
        assertEquals(1, result.rejectedCount());
    }
}
