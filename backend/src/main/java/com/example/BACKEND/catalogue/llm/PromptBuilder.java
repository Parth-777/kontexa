package com.example.BACKEND.catalogue.llm;

import com.example.BACKEND.catalogue.model.ColumnInfo;
import com.example.BACKEND.catalogue.model.TableInfo;
import org.springframework.stereotype.Component;

/**
 * PromptBuilder
 *
 * Constructs the system and user prompts sent to the LLM for catalogue enrichment.
 *
 * The prompts are fully generic — they describe the structure and values of any
 * table from any domain (analytics events, e-commerce orders, Kaggle datasets,
 * HR data, financial data, etc.) and ask the LLM to generate enrichment for each column.
 *
 * One API call is made per TABLE (not per column) so the LLM has full context
 * about all columns at once — it can understand relationships between columns
 * and generate better descriptions.
 *
 * Example: Knowing "survived" and "pclass" and "age" are in the same table
 * tells the LLM it's a Titanic dataset — it enriches accordingly.
 */
@Component
public class PromptBuilder {

    /**
     * System prompt — tells the LLM its role.
     * Generic — same for every table, every client.
     */
    public String buildSystemPrompt() {
        return """
                You are an expert data analyst and business intelligence specialist.
                
                Your job is to enrich database column metadata to make it understandable
                by non-technical users such as product managers, business analysts, and founders
                who will query this data using plain English.
                
                You will be given a database table with its columns, data types, and sampled values.
                
                For each column, you must generate:
                
                1. description
                   A clear, one-sentence plain English description of what this column stores.
                   Use business language, not technical jargon.
                   Be specific — use the sample values as context clues.
                
                2. synonyms
                   A list of 5-8 English words or phrases that a non-technical user might say
                   when referring to this column. Think about how a PM or founder would ask about it.
                   Examples: for "order_status" → ["status", "order state", "delivered", "pending orders"]
                             for "page_url" → ["website", "url", "page", "where they went", "site visited"]
                             for "survived" (Titanic) → ["did they survive", "survival", "alive", "made it"]
                
                3. valueMeanings
                   If the column contains coded, abbreviated, or non-obvious values,
                   provide a plain English meaning for each value.
                   Examples: {"IN": "India", "US": "United States"}
                             {"1": "Survived", "0": "Did not survive"}
                             {"pending": "Order placed but not yet processed"}
                             {"pclass_1": "First class (luxury)"}
                   Leave this empty {} if the values are already self-explanatory.
                
                4. role
                   Classify this column's primary role in queries. Choose exactly one:
                   - "dimension"   → categorical column used for grouping/breakdown (e.g. country, status, category)
                   - "metric"      → numeric column used for aggregation (e.g. revenue, count, price, age)
                   - "filter"      → column typically used to filter results (e.g. event_name, vendor, survived)
                   - "timestamp"   → date or time column for time-based filtering (e.g. created_at, event_time)
                   - "identifier"  → unique ID or key, not useful for analysis (e.g. id, uuid, token)
                   - "freetext"    → long text with too many unique values (e.g. description, notes, raw_payload)
                
                IMPORTANT RULES:
                - Return ONLY valid JSON. No explanation text outside the JSON.
                - Use exactly the field names: columnName, description, synonyms, valueMeanings, role
                - Every column in the input MUST appear in the output — no exceptions
                - If a column has no sample values, infer its meaning from the column name and table context
                - Base your enrichment on the sample values provided — they reveal the true meaning
                - If sample values suggest a specific domain (e-commerce, healthcare, travel, analytics,
                  entertainment), use that domain knowledge to generate richer descriptions and synonyms
                - Never leave a column out of the response even if you have no sample data for it
                """;
    }

    /**
     * User prompt — describes a specific table and all its columns to the LLM.
     * Built dynamically from the TableInfo object.
     * Works for any table from any domain.
     *
     * @param table  TableInfo populated by SchemaDiscovery + DataSampler
     * @return       Formatted prompt string describing the table
     */
    public String buildUserPrompt(TableInfo table) {
        StringBuilder sb = new StringBuilder();

        sb.append("Table name: ").append(table.getTableName()).append("\n");
        sb.append("Schema: ").append(table.getTableSchema()).append("\n");
        if (table.getRowCount() > 0) {
            sb.append("Total rows: ").append(table.getRowCount()).append("\n");
        }
        sb.append("\n");
        sb.append("Columns:\n");

        int index = 1;
        for (ColumnInfo col : table.getColumns()) {
            sb.append(index++).append(". ");
            sb.append(col.getColumnName());
            sb.append(" (").append(col.getDataType()).append(")");

            // Add sample values if available
            if (col.getSampleValues() != null && !col.getSampleValues().isEmpty()) {
                sb.append("\n   Sample values: ")
                  .append(String.join(", ", col.getSampleValues()));
            }

            // Add numeric range if available
            if (col.getMinValue() != null && col.getMaxValue() != null
                    && col.getAvgValue() != null) {
                sb.append("\n   Range: min=").append(col.getMinValue())
                  .append(", max=").append(col.getMaxValue())
                  .append(", avg=").append(col.getAvgValue());
            }

            // Add timestamp range if available (no avg for timestamps)
            if (col.getMinValue() != null && col.getMaxValue() != null
                    && col.getAvgValue() == null && !col.isSkipped()) {
                sb.append("\n   Date range: ").append(col.getMinValue())
                  .append(" → ").append(col.getMaxValue());
            }

            // Note if column was skipped — but still needs enrichment
            if (col.isSkipped()) {
                sb.append("\n   [No sample values available — enrich based on column name and table context]");
            }

            sb.append("\n");
        }

        sb.append("\n");
        sb.append("Enrich ALL columns listed above.\n");
        sb.append("Return JSON in this exact format:\n");
        sb.append("""
                {
                  "columns": [
                    {
                      "columnName": "exact_column_name_from_above",
                      "description": "Plain English description",
                      "synonyms": ["word1", "word2", "phrase three"],
                      "valueMeanings": {"coded_value": "plain meaning"},
                      "role": "dimension|metric|filter|timestamp|identifier|freetext"
                    }
                  ]
                }
                """);

        return sb.toString();
    }
}
