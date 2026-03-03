package com.example.BACKEND.analytics.schema;

import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * SchemaRegistry is the single source of truth about the canonical_events table
 * for the LLM layer.
 *
 * It stores every column with:
 *  - its exact DB name
 *  - data type
 *  - human-readable description
 *  - example values (real values from the database)
 *  - English synonyms a PM might say
 *
 * This registry is used by LLMIntentExtractor (Step 2) to build schema-aware
 * prompts so the LLM can correctly map English → exact DB columns and values.
 *
 * It does NOT replace CanonicalFieldRegistry — that handles vendor mapping
 * (Amplitude vs Mixpanel). This handles English → DB column mapping for the LLM.
 */
@Component
public class SchemaRegistry {

    // Using LinkedHashMap to preserve insertion order in prompts
    private final Map<String, SchemaField> fields = new LinkedHashMap<>();

    public SchemaRegistry() {
        registerFields();
    }

    private void registerFields() {

        // ---- EVENT IDENTITY ----

        register(new SchemaField(
                "event_name",
                "text",
                "The type of analytics event that occurred",
                List.of("page_view", "signup_started", "signup_completed",
                        "purchase", "login", "button_click",
                        "add_to_cart", "video_play", "video_pause", "like_post"),
                List.of("event", "action", "trigger", "what happened",
                        "clicked", "visited", "signed up", "purchased",
                        "logged in", "viewed", "played")
        ));

        register(new SchemaField(
                "event_time",
                "timestamp",
                "Client-side timestamp of when the event occurred",
                List.of("2026-01-30T09:10:00Z", "2026-02-10T18:35:00Z"),
                List.of("when", "time", "date", "occurred at",
                        "last 7 days", "last 30 days", "today", "yesterday")
        ));

        register(new SchemaField(
                "ingested_at",
                "timestamp",
                "Server-side timestamp of when the event was ingested",
                List.of("2026-01-30T09:11:00Z"),
                List.of("received at", "server time", "ingestion time")
        ));

        // ---- PAGE CONTEXT ----

        register(new SchemaField(
                "page_url",
                "text",
                "Full URL of the page where the event occurred",
                List.of("https://netflix.com/home", "https://twitter.com/profile",
                        "https://amazon.com/checkout", "https://instagram.com/signup",
                        "https://spotify.com/home"),
                List.of("website", "url", "site", "domain", "netflix", "twitter",
                        "amazon", "instagram", "spotify", "page link")
        ));

        register(new SchemaField(
                "page_location",
                "text",
                "Path component of the URL (page path)",
                List.of("/home", "/profile", "/login", "/checkout",
                        "/product", "/search", "/signup", "/watch", "/post"),
                List.of("page", "path", "section", "screen",
                        "home page", "profile page", "login page",
                        "checkout page", "product page")
        ));

        register(new SchemaField(
                "page_title",
                "text",
                "Title of the page where the event occurred",
                List.of("Home", "Profile", "Login", "Checkout",
                        "Product", "Search", "Signup", "Watch"),
                List.of("page name", "title", "page heading")
        ));

        register(new SchemaField(
                "referring_domain",
                "text",
                "Domain that referred the user to the page",
                List.of("google.com", "facebook.com", "twitter.com",
                        "reddit.com", "amazon.com", "direct"),
                List.of("came from", "source", "referrer", "referred by",
                        "traffic from", "from google", "from facebook")
        ));

        register(new SchemaField(
                "screen_name",
                "text",
                "Mobile screen name where the event occurred",
                List.of("HomeScreen", "ProfileScreen", "CartScreen",
                        "LoginScreen", "SignupScreen", "SearchScreen"),
                List.of("screen", "mobile screen", "app screen", "view")
        ));

        // ---- VENDOR & SCHEMA ----

        register(new SchemaField(
                "vendor",
                "text",
                "Analytics vendor that captured the event",
                List.of("AMPLITUDE", "MIXPANEL"),
                List.of("tool", "analytics tool", "provider",
                        "amplitude", "mixpanel",
                        "amplitude vs mixpanel", "by vendor")
        ));

        register(new SchemaField(
                "schema_version",
                "text",
                "Version of the canonical schema used",
                List.of("V1"),
                List.of("version", "schema version")
        ));

        // ---- USER IDENTITY ----

        register(new SchemaField(
                "user_id",
                "text",
                "Unique identifier for the authenticated user",
                List.of("user_abc123", "usr_9f3d2a"),
                List.of("user", "who", "person", "customer",
                        "unique users", "distinct users", "user count")
        ));

        // ---- RAW PAYLOAD ----

        register(new SchemaField(
                "raw_payload",
                "jsonb",
                "Original raw event payload from the vendor",
                List.of("{\"os\": \"android\", \"device\": \"mobile\"}",
                        "{\"button\": \"shop_now\"}"),
                List.of("raw data", "payload", "original event", "metadata")
        ));
    }

    private void register(SchemaField field) {
        fields.put(field.getColumnName(), field);
    }

    /**
     * Returns all registered schema fields.
     */
    public List<SchemaField> getAllFields() {
        return List.copyOf(fields.values());
    }

    /**
     * Returns a specific field by its DB column name.
     */
    public SchemaField getField(String columnName) {
        return fields.get(columnName);
    }

    /**
     * Builds the full schema description block to inject into an LLM prompt.
     *
     * Example output:
     *   Table: public.canonical_events
     *   Columns:
     *   - event_name (text): The type of analytics event. Examples: page_view, signup_started...
     *   - page_url (text): Full URL of the page. Examples: https://netflix.com/home...
     *   ...
     */
    public String buildPromptSchemaBlock() {
        StringBuilder sb = new StringBuilder();
        sb.append("Table: public.canonical_events\n");
        sb.append("Columns:\n");

        for (SchemaField field : fields.values()) {
            sb.append(field.toPromptLine()).append("\n");
        }

        return sb.toString();
    }

    /**
     * Returns a list of all known event_name values for use in prompts.
     */
    public List<String> getKnownEventNames() {
        SchemaField eventNameField = fields.get("event_name");
        if (eventNameField == null) return List.of();
        return eventNameField.getExampleValues();
    }

    /**
     * Returns a list of all known domain values extracted from page_url examples.
     */
    public List<String> getKnownDomains() {
        SchemaField pageUrlField = fields.get("page_url");
        if (pageUrlField == null) return List.of();
        return pageUrlField.getExampleValues().stream()
                .map(url -> {
                    // Extract domain from full URL e.g. https://netflix.com/home → netflix.com
                    try {
                        String withoutProtocol = url.replace("https://", "").replace("http://", "");
                        return withoutProtocol.split("/")[0];
                    } catch (Exception e) {
                        return url;
                    }
                })
                .distinct()
                .collect(Collectors.toList());
    }
}
