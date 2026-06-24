package com.example.BACKEND.catalogue.charts;

import java.util.List;

/** Response for GET /api/agent/charts — sidebar chart stack beside the Agent Feed. */
public class ChartPanelResponse {

    private List<ChartPanelItem> charts;
    private int totalInsights;
    private int chartsAvailable;

    public ChartPanelResponse() {}

    public ChartPanelResponse(List<ChartPanelItem> charts, int totalInsights, int chartsAvailable) {
        this.charts = charts;
        this.totalInsights = totalInsights;
        this.chartsAvailable = chartsAvailable;
    }

    public List<ChartPanelItem> getCharts() { return charts; }
    public void setCharts(List<ChartPanelItem> charts) { this.charts = charts; }
    public int getTotalInsights() { return totalInsights; }
    public void setTotalInsights(int totalInsights) { this.totalInsights = totalInsights; }
    public int getChartsAvailable() { return chartsAvailable; }
    public void setChartsAvailable(int chartsAvailable) { this.chartsAvailable = chartsAvailable; }
}
