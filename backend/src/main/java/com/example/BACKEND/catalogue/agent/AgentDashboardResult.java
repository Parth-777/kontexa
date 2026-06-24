package com.example.BACKEND.catalogue.agent;

import com.example.BACKEND.catalogue.charts.ChartSpec;

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
    /** One-line leadership brief — top 3 material findings. */
    private String             dailyBrief;

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

    public static class MetricHighlight {
        private String label;
        private String value;

        public MetricHighlight() {}
        public MetricHighlight(String label, String value) {
            this.label = label;
            this.value = value;
        }
        public String getLabel() { return label; }
        public String getValue() { return value; }
    }

    public static class InsightCard {
        /** Populated after DB persistence — null for cards not yet saved. */
        private String id;
        private String title;
        private String description;
        private String impactLevel;       // HIGH | MEDIUM | LOW | POSITIVE
        private String badge;             // ALERT | RISK | OPPORTUNITY | INFO
        private String agentName;         // e.g. "Revenue agent"
        private List<MetricHighlight> metricHighlights;   // up to 3 mini KPI chips
        private List<String> suggestedActions;            // up to 3 action labels

        public InsightCard() {}
        public InsightCard(String title, String description, String impactLevel) {
            this.title       = title;
            this.description = description;
            this.impactLevel = impactLevel;
        }

        public String getId()          { return id; }
        public void   setId(String v)  { this.id = v; }
        public String getTitle()       { return title; }
        public void   setTitle(String v) { this.title = v; }
        public String getDescription() { return description; }
        public void   setDescription(String v) { this.description = v; }
        public String getImpactLevel() { return impactLevel; }
        public void   setImpactLevel(String v) { this.impactLevel = v; }
        public String getBadge()       { return badge; }
        public void   setBadge(String v) { this.badge = v; }
        public String getAgentName()   { return agentName; }
        public void   setAgentName(String v) { this.agentName = v; }
        public List<MetricHighlight> getMetricHighlights() { return metricHighlights; }
        public void setMetricHighlights(List<MetricHighlight> v) { this.metricHighlights = v; }
        public List<String> getSuggestedActions() { return suggestedActions; }
        public void setSuggestedActions(List<String> v) { this.suggestedActions = v; }

        /** Why this insight occurred — 2-3 data-backed reasons */
        private List<String> reasons;
        /** What to do about it — 2-3 specific, actionable strategies */
        private List<String> strategies;
        /** Actual column names from the data the LLM cited as the basis for this insight */
        private List<String> sourceColumns;

        /** Optional chart spec for this card (rendered by frontend). */
        private ChartSpec chart;

        public List<String> getReasons()             { return reasons; }
        public void setReasons(List<String> v)       { this.reasons = v; }
        public List<String> getStrategies()          { return strategies; }
        public void setStrategies(List<String> v)    { this.strategies = v; }
        public List<String> getSourceColumns()       { return sourceColumns; }
        public void setSourceColumns(List<String> v) { this.sourceColumns = v; }

        public ChartSpec getChart()                  { return chart; }
        public void setChart(ChartSpec v)            { this.chart = v; }
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

    public String  getDailyBrief()           { return dailyBrief; }
    public void    setDailyBrief(String v)   { this.dailyBrief = v; }
}
