package com.example.BACKEND.catalogue.charts;

import java.util.UUID;

/**
 * One chart in the Agent Feed sidebar panel, linked to an insight card.
 */
public class ChartPanelItem {

    private UUID insightId;
    private String title;
    private String description;
    private String badge;
    private String agentName;
    private ChartSpec chart;

    public ChartPanelItem() {}

    public ChartPanelItem(UUID insightId, String title, String description,
                          String badge, String agentName, ChartSpec chart) {
        this.insightId = insightId;
        this.title = title;
        this.description = description;
        this.badge = badge;
        this.agentName = agentName;
        this.chart = chart;
    }

    public UUID getInsightId() { return insightId; }
    public void setInsightId(UUID insightId) { this.insightId = insightId; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public String getBadge() { return badge; }
    public void setBadge(String badge) { this.badge = badge; }
    public String getAgentName() { return agentName; }
    public void setAgentName(String agentName) { this.agentName = agentName; }
    public ChartSpec getChart() { return chart; }
    public void setChart(ChartSpec chart) { this.chart = chart; }
}
