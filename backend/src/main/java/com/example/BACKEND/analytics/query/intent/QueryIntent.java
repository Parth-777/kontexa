package com.example.BACKEND.analytics.query.intent;

import com.example.BACKEND.analytics.query.enums.*;

public class QueryIntent {

    private EntityType entity;
    private String eventName;
    private MetricType metric;
    private TimeRangeType timeRange;
    private String timeRangeValue; // For RELATIVE type, stores the number of days
    // Domain and action type are used for brand / site based queries
    // e.g. "visited netflix", "signed up on twitter"
    private String domain;
    private String actionType;
    private String pageType; // e.g. "home", "profile", "login" for page-specific queries
    private String groupByField; // e.g. "vendor", "page_url" for GROUP BY queries

    public EntityType getEntity() {
        return entity;
    }

    public void setEntity(EntityType entity) {
        this.entity = entity;
    }

    public String getEventName() {
        return eventName;
    }

    public void setEventName(String eventName) {
        this.eventName = eventName;
    }

    public MetricType getMetric() {
        return metric;
    }

    public void setMetric(MetricType metric) {
        this.metric = metric;
    }

    public TimeRangeType getTimeRange() {
        return timeRange;
    }

    public void setTimeRange(TimeRangeType timeRange) {
        this.timeRange = timeRange;
    }

    public String getTimeRangeValue() {
        return timeRangeValue;
    }

    public void setTimeRangeValue(String timeRangeValue) {
        this.timeRangeValue = timeRangeValue;
    }

    public String getDomain() {
        return domain;
    }

    public void setDomain(String domain) {
        this.domain = domain;
    }

    public String getActionType() {
        return actionType;
    }

    public void setActionType(String actionType) {
        this.actionType = actionType;
    }

    public String getPageType() {
        return pageType;
    }

    public void setPageType(String pageType) {
        this.pageType = pageType;
    }

    public String getGroupByField() {
        return groupByField;
    }

    public void setGroupByField(String groupByField) {
        this.groupByField = groupByField;
    }
   /* public boolean hasEventSignals() {
        return
                this.getMetric().stream().anyMatch(m ->
                        m.getField().startsWith("event")
                )
                        ||
                        this.getFilters().stream().anyMatch(f ->
                                f.getField().startsWith("event")
                                        || f.getField().equals("page_location")
                                        || f.getField().equals("page_url")
                        )
                        ||
                        this.getRawText().toLowerCase().contains("click")
                        ||
                        this.getRawText().toLowerCase().contains("button");
    }

    */
    public boolean usesEventFields() {
        return eventName != null || timeRange != null;
    }

}
