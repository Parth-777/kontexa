package com.example.BACKEND.analytics.query.builder;

import com.example.BACKEND.analytics.query.*;
import com.example.BACKEND.analytics.query.enums.*;
import com.example.BACKEND.analytics.query.intent.QueryIntent;

import java.util.ArrayList;
import java.util.List;
public class CqlIntentBuilder {

    public static CanonicalQuery build(QueryIntent intent) {

        CanonicalQuery cql = new CanonicalQuery();

        /* -----------------------------
         * 1. ENTITY (default EVENT)
         * ----------------------------- */
        cql.setEntity(EntityType.EVENT);

        /* -----------------------------
         * 2. METRIC (default COUNT)
         * ----------------------------- */
        Metric metric = new Metric();
        metric.setType(
                intent.getMetric() != null
                        ? intent.getMetric()
                        : MetricType.COUNT
        );
        metric.setField(null); // COUNT(*)
        cql.setMetrics(List.of(metric));
// -------------------- FILTERS --------------------
        List<FilterCondition> filters = new ArrayList<>();

        String eventName = intent.getEventName();

        if (eventName != null) {

            String normalized = eventName.toLowerCase();

            // Semantic: home button click
            if (normalized.contains("home")
                    && normalized.contains("click")) {

                // event_name = 'button_click'
                FilterCondition eventFilter = new FilterCondition();
                eventFilter.setField("event_name");
                eventFilter.setOperator(Operator.EQUALS);
                eventFilter.setValue("button_click");
                filters.add(eventFilter);

                // page_location = '/home'
                FilterCondition pageFilter = new FilterCondition();
                pageFilter.setField("page_location");
                pageFilter.setOperator(Operator.EQUALS);
                pageFilter.setValue("/home");
                filters.add(pageFilter);

            } else {
                // fallback: exact event_name
                FilterCondition filter = new FilterCondition();
                filter.setField("event_name");
                filter.setOperator(Operator.EQUALS);
                filter.setValue(eventName);
                filters.add(filter);
            }
        }

        // Handle action type to determine event_name (works with or without domain)
        String actionType = intent.getActionType();
        if (actionType != null) {
            String eventNameValue;
            if ("signup".equals(actionType)) {
                eventNameValue = "signup_started";
            } else if ("purchase".equals(actionType)) {
                eventNameValue = "purchase";
            } else if ("login".equals(actionType)) {
                eventNameValue = "login";
            } else if ("add_to_cart".equals(actionType)) {
                eventNameValue = "add_to_cart";
            } else {
                // default to page_view for visit-style queries
                eventNameValue = "page_view";
            }

            // event_name filter based on action
            FilterCondition eventFilter = new FilterCondition();
            eventFilter.setField("event_name");
            eventFilter.setOperator(Operator.EQUALS);
            eventFilter.setValue(eventNameValue);
            filters.add(eventFilter);
        }

        // Semantic: brand / domain based queries like
        // "visited netflix", "visited twitter", "signed up on netflix"
        String domain = intent.getDomain();
        if (domain != null) {
            String fullDomain = mapDomainToFullDomain(domain);

            // page_url CONTAINS 'netflix.com' / 'twitter.com' / etc.
            FilterCondition domainFilter = new FilterCondition();
            domainFilter.setField("page_url");
            domainFilter.setOperator(Operator.CONTAINS);
            domainFilter.setValue(fullDomain);
            filters.add(domainFilter);

            // Handle page type filters (e.g. "home page", "profile page")
            String pageType = intent.getPageType();
            if (pageType != null) {
                String pageLocation = mapPageTypeToLocation(pageType);
                if (pageLocation != null) {
                    FilterCondition pageTypeFilter = new FilterCondition();
                    pageTypeFilter.setField("page_location");
                    pageTypeFilter.setOperator(Operator.EQUALS);
                    pageTypeFilter.setValue(pageLocation);
                    filters.add(pageTypeFilter);
                }
            }
        }

        // Handle GROUP BY queries (e.g. "by vendor", "by page_url")
        String groupByField = intent.getGroupByField();
        if (groupByField != null) {
            cql.setGroupBy(List.of(groupByField));
        }

        if(!filters.isEmpty()) {
            cql.setFilters(filters);
        }
                /* -----------------------------
         * 4. TIME RANGE
         * ----------------------------- */
        if (intent.getTimeRange() != null) {
            TimeRange tr = new TimeRange();
            tr.setType(intent.getTimeRange());
            // If RELATIVE type, store the number of days in the value field
            if (intent.getTimeRange() == TimeRangeType.RELATIVE && intent.getTimeRangeValue() != null) {
                tr.setValue(intent.getTimeRangeValue());
            }
            cql.setTimeRange(tr);
        }

        /* -----------------------------
         * 5. LIMIT
         * ----------------------------- */
        cql.setLimit(100);

        return cql;
    }

    private static String mapDomainToFullDomain(String domain) {
        String normalized = domain.toLowerCase();
        return switch (normalized) {
            case "netflix" -> "netflix.com";
            case "twitter" -> "twitter.com";
            case "amazon" -> "amazon.com";
            case "instagram" -> "instagram.com";
            case "spotify" -> "spotify.com";
            default -> normalized;
        };
    }

    /**
     * Maps page type to page_location value
     * e.g. "home" -> "/home", "profile" -> "/profile"
     */
    private static String mapPageTypeToLocation(String pageType) {
        String normalized = pageType.toLowerCase();
        return switch (normalized) {
            case "home" -> "/home";
            case "profile" -> "/profile";
            case "login" -> "/login";
            case "product" -> "/product";
            case "checkout" -> "/checkout";
            default -> null;
        };
    }
}

