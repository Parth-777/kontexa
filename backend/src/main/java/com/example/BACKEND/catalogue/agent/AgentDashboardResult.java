package com.example.BACKEND.catalogue.agent;

import java.util.List;

/**
 * Full response from the agentic dashboard analysis.
 * Returned by POST /api/agent/dashboard.
 */
public class AgentDashboardResult {

    private List<KpiCard>      kpiCards;
    private List<InsightCard>  insights;
    private List<Investigation> investigations;
    private List<Anomaly>      anomalies;
    private List<String>       followUpQuestions;
    private List<String>       tablesUsed;
    private String             reasoning;
    private int                confidence;
    private String             dataSource;
    private String             lastUpdated;
    private String             errorMessage;

    // ── Nested types ─────────────────────────────────────────────────────────

    public static class KpiCard {
        private String metric;
        private String displayValue;
        private double rawValue;
        private double previousValue;
        private double changePercent;
        private String direction;   // UP | DOWN | FLAT

        public KpiCard() {}
        public KpiCard(String metric, String displayValue, double rawValue,
                       double previousValue, double changePercent, String direction) {
            this.metric        = metric;
            this.displayValue  = displayValue;
            this.rawValue      = rawValue;
            this.previousValue = previousValue;
            this.changePercent = changePercent;
            this.direction     = direction;
        }

        public String getMetric()         { return metric; }
        public String getDisplayValue()   { return displayValue; }
        public double getRawValue()       { return rawValue; }
        public double getPreviousValue()  { return previousValue; }
        public double getChangePercent()  { return changePercent; }
        public String getDirection()      { return direction; }
    }

    public static class InsightCard {
        private String title;
        private String description;
        private String impactLevel;   // HIGH | MEDIUM | LOW | POSITIVE

        public InsightCard() {}
        public InsightCard(String title, String description, String impactLevel) {
            this.title       = title;
            this.description = description;
            this.impactLevel = impactLevel;
        }

        public String getTitle()       { return title; }
        public String getDescription() { return description; }
        public String getImpactLevel() { return impactLevel; }
    }

    public static class Investigation {
        private String title;
        private String description;
        private String status;   // IN_PROGRESS | SUGGESTED

        public Investigation() {}
        public Investigation(String title, String description, String status) {
            this.title       = title;
            this.description = description;
            this.status      = status;
        }

        public String getTitle()       { return title; }
        public String getDescription() { return description; }
        public String getStatus()      { return status; }
    }

    public static class Anomaly {
        private String metric;
        private String description;
        private double changePercent;
        private String direction;

        public Anomaly() {}
        public Anomaly(String metric, String description, double changePercent, String direction) {
            this.metric        = metric;
            this.description   = description;
            this.changePercent = changePercent;
            this.direction     = direction;
        }

        public String getMetric()        { return metric; }
        public String getDescription()   { return description; }
        public double getChangePercent() { return changePercent; }
        public String getDirection()     { return direction; }
    }

    // ── Getters / Setters ────────────────────────────────────────────────────

    public List<KpiCard>       getKpiCards()          { return kpiCards; }
    public void setKpiCards(List<KpiCard> v)          { this.kpiCards = v; }

    public List<InsightCard>   getInsights()          { return insights; }
    public void setInsights(List<InsightCard> v)      { this.insights = v; }

    public List<Investigation> getInvestigations()    { return investigations; }
    public void setInvestigations(List<Investigation> v) { this.investigations = v; }

    public List<Anomaly>       getAnomalies()         { return anomalies; }
    public void setAnomalies(List<Anomaly> v)         { this.anomalies = v; }

    public List<String>        getFollowUpQuestions() { return followUpQuestions; }
    public void setFollowUpQuestions(List<String> v)  { this.followUpQuestions = v; }

    public List<String>        getTablesUsed()        { return tablesUsed; }
    public void setTablesUsed(List<String> v)         { this.tablesUsed = v; }

    public String  getReasoning()         { return reasoning; }
    public void    setReasoning(String v) { this.reasoning = v; }

    public int     getConfidence()         { return confidence; }
    public void    setConfidence(int v)    { this.confidence = v; }

    public String  getDataSource()         { return dataSource; }
    public void    setDataSource(String v) { this.dataSource = v; }

    public String  getLastUpdated()         { return lastUpdated; }
    public void    setLastUpdated(String v) { this.lastUpdated = v; }

    public String  getErrorMessage()         { return errorMessage; }
    public void    setErrorMessage(String v) { this.errorMessage = v; }
}
