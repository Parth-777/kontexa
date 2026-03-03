package com.example.BACKEND.analytics.schema;

import java.util.List;

/**
 * Represents a single column in the canonical_events table.
 * This is used to build schema-aware prompts for the LLM so it knows
 * exactly what fields exist, what values they hold, and what English
 * words a PM might use to refer to them.
 */
public class SchemaField {

    private final String columnName;       // Actual DB column name e.g. "event_name"
    private final String dataType;         // SQL type e.g. "text", "timestamp"
    private final String description;      // Human-readable description
    private final List<String> exampleValues;  // Real values from the DB
    private final List<String> synonyms;   // English words a user might say for this field

    public SchemaField(
            String columnName,
            String dataType,
            String description,
            List<String> exampleValues,
            List<String> synonyms
    ) {
        this.columnName = columnName;
        this.dataType = dataType;
        this.description = description;
        this.exampleValues = exampleValues;
        this.synonyms = synonyms;
    }

    public String getColumnName() {
        return columnName;
    }

    public String getDataType() {
        return dataType;
    }

    public String getDescription() {
        return description;
    }

    public List<String> getExampleValues() {
        return exampleValues;
    }

    public List<String> getSynonyms() {
        return synonyms;
    }

    /**
     * Builds a single-line summary of this field for use inside an LLM prompt.
     * Example output:
     *   event_name (text): Name of the analytics event. Examples: page_view, signup_started, purchase.
     *   Synonyms: event, action, trigger.
     */
    public String toPromptLine() {
        StringBuilder sb = new StringBuilder();
        sb.append("- ").append(columnName).append(" (").append(dataType).append("): ");
        sb.append(description).append(".");

        if (exampleValues != null && !exampleValues.isEmpty()) {
            sb.append(" Examples: ").append(String.join(", ", exampleValues)).append(".");
        }

        if (synonyms != null && !synonyms.isEmpty()) {
            sb.append(" A user might say: ").append(String.join(", ", synonyms)).append(".");
        }

        return sb.toString();
    }
}
