package com.example.BACKEND.analytics.query.parser;

import com.example.BACKEND.analytics.query.enums.*;
import com.example.BACKEND.analytics.query.intent.QueryIntent;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class EnglishQueryParser {

    // Pattern to match "last X days" where X is any number
    private static final Pattern LAST_DAYS_PATTERN = Pattern.compile("last\\s+(\\d+)\\s+days?");

    public static QueryIntent parse(String text) {

        text = text.toLowerCase();

        QueryIntent intent = new QueryIntent();

        // ---- ENTITY ----
        if (text.contains("user")) {
            intent.setEntity(EntityType.USER);
        } else {
            intent.setEntity(EntityType.EVENT);
        }

        // ---- METRIC ----
        if (text.contains("how many") || text.contains("count")) {
            intent.setMetric(MetricType.COUNT);
        }

        // ---- TIME RANGE ----
        // First check for specific hardcoded patterns
        if (text.contains("last 7 days")) {
            intent.setTimeRange(TimeRangeType.LAST_7_DAYS);
        } else if (text.contains("last 30 days")) {
            intent.setTimeRange(TimeRangeType.LAST_30_DAYS);
        } else if (text.contains("today")) {
            intent.setTimeRange(TimeRangeType.TODAY);
        } else {
            // Try to match generic "last X days" pattern
            Matcher matcher = LAST_DAYS_PATTERN.matcher(text);
            if (matcher.find()) {
                String daysStr = matcher.group(1);
                // Store the number of days in a custom field for RELATIVE type
                intent.setTimeRange(TimeRangeType.RELATIVE);
                intent.setTimeRangeValue(daysStr);
            }
        }

        // ---- EVENT NAME ----
        if (text.contains("home")) {
            intent.setEventName("Home Clicked");
        }

        // ---- DOMAIN + ACTION TYPE ----
        // Detect brand / site mentioned in the query (expanded domains)
        if (text.contains("netflix")) {
            intent.setDomain("netflix");
        } else if (text.contains("twitter")) {
            intent.setDomain("twitter");
        } else if (text.contains("amazon")) {
            intent.setDomain("amazon");
        } else if (text.contains("instagram")) {
            intent.setDomain("instagram");
        } else if (text.contains("spotify")) {
            intent.setDomain("spotify");
        }

        // Detect high-level action type
        // "signed up on netflix", "sign up on twitter", "signup on netflix"
        if (text.contains("signed up")
                || text.contains("sign up")
                || text.contains("signup")) {
            intent.setActionType("signup");
        } else if (text.contains("visited") || text.contains("visit") 
                || text.contains("views") || text.contains("viewed") 
                || text.contains("most views")) {
            // "visited netflix", "visit twitter", "which pages got the most views"
            intent.setActionType("visit");
        } else if (text.contains("purchased") || text.contains("purchase") || text.contains("bought")) {
            intent.setActionType("purchase");
        } else if (text.contains("logged in") || text.contains("login")) {
            intent.setActionType("login");
        } else if (text.contains("added to cart") || text.contains("add to cart") || text.contains("add_to_cart")) {
            intent.setActionType("add_to_cart");
        }

        // ---- PAGE TYPE DETECTION ----
        // Detect specific page types: "home page", "profile page", "login page"
        if (text.contains("home page") || (text.contains("home") && text.contains("page"))) {
            intent.setPageType("home");
        } else if (text.contains("profile page") || (text.contains("profile") && text.contains("page"))) {
            intent.setPageType("profile");
        } else if (text.contains("login page") || (text.contains("login") && text.contains("page"))) {
            intent.setPageType("login");
        } else if (text.contains("product page") || (text.contains("product") && text.contains("page"))) {
            intent.setPageType("product");
        } else if (text.contains("checkout page") || (text.contains("checkout") && text.contains("page"))) {
            intent.setPageType("checkout");
        }

        // ---- GROUP BY DETECTION ----
        // Detect GROUP BY hints: "by vendor", "by page_url", "which pages", "amplitude vs mixpanel"
        if (text.contains("by vendor") || text.contains("amplitude vs mixpanel") || text.contains("mixpanel vs amplitude")) {
            intent.setGroupByField("vendor");
        } else if (text.contains("by page") || text.contains("which pages") || text.contains("by page_url") || text.contains("pages got")) {
            intent.setGroupByField("page_url");
        } else if (text.contains("by page_location") || text.contains("by location")) {
            intent.setGroupByField("page_location");
        } else if (text.contains("by event") || text.contains("by event_name")) {
            intent.setGroupByField("event_name");
        }

        return intent;
    }
}
