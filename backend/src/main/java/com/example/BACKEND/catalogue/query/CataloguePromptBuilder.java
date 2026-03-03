package com.example.BACKEND.catalogue.query;

import com.example.BACKEND.catalogue.entity.CatalogueColumnEntity;
import com.example.BACKEND.catalogue.entity.CatalogueTableEntity;
import com.example.BACKEND.catalogue.entity.ClientCatalogueEntity;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;

import java.time.LocalDate;

/**
 * Builds the LLM prompts used at query time.
 *
 * Two variants:
 *  - buildSystemPromptFromSnapshot()  → reads from the snapshot JSON (fast, used at runtime)
 *  - buildSystemPrompt()              → reads from JPA entities (kept for compatibility)
 *
 * The LLM is asked to return {"sql": "..."} — a JSON-wrapped SQL query.
 */
@Component
public class CataloguePromptBuilder {

    /**
     * Build system prompt from the catalogue snapshot JSON.
     * This is the primary method used at query time.
     *
     * @param catalogueNode  the parsed snapshot JSON from catalogue_snapshots.catalogue_json
     */
    public String buildSystemPromptFromSnapshot(JsonNode catalogueNode) {
        StringBuilder sb = new StringBuilder();

        sb.append("You are an expert SQL analyst. Your job is to convert a plain-English question into a valid PostgreSQL query.\n\n");
        sb.append("Today's date is ").append(LocalDate.now()).append(".\n\n");
        sb.append("DATABASE SCHEMA\n");
        sb.append("==============\n");
        sb.append("Schema: ").append(catalogueNode.path("schemaName").asText("public")).append("\n\n");

        for (JsonNode table : catalogueNode.path("tables")) {
            String tableSchema = table.path("tableSchema").asText("public");
            String tableName   = table.path("tableName").asText();
            String tableDesc   = table.path("description").asText("");

            sb.append("TABLE: ").append(tableSchema).append(".").append(tableName).append("\n");
            if (!tableDesc.isBlank()) {
                sb.append("  Description: ").append(tableDesc).append("\n");
            }
            sb.append("  Columns:\n");

            for (JsonNode col : table.path("columns")) {
                String colName      = col.path("columnName").asText();
                String dataType     = col.path("dataType").asText();
                String description  = col.path("description").asText("");
                String role         = col.path("role").asText("");
                String samples      = col.path("sampleValues").asText("[]");
                String meanings     = col.path("valueMeanings").asText("{}");
                String synonyms     = col.path("synonyms").asText("[]");
                String minVal       = col.path("minValue").asText("");
                String maxVal       = col.path("maxValue").asText("");

                sb.append("    - ").append(colName).append(" (").append(dataType).append(")");

                if (!description.isBlank()) sb.append(" → ").append(description);
                if (!role.isBlank())        sb.append(" [role: ").append(role).append("]");

                if (!samples.equals("[]") && !samples.isBlank()) {
                    String cleaned = samples.replace("[","").replace("]","").replace("\"","");
                    sb.append(" | samples: ").append(cleaned);
                }
                if (!meanings.equals("{}") && !meanings.isBlank()) {
                    String cleaned = meanings.replace("{","").replace("}","").replace("\"","");
                    sb.append(" | meanings: ").append(cleaned);
                }
                if (!synonyms.equals("[]") && !synonyms.isBlank()) {
                    String cleaned = synonyms.replace("[","").replace("]","").replace("\"","");
                    sb.append(" | also called: ").append(cleaned);
                }
                if (!minVal.isBlank() || !maxVal.isBlank()) {
                    sb.append(" | range: ").append(minVal).append(" to ").append(maxVal);
                }

                sb.append("\n");
            }
            sb.append("\n");
        }

        appendRules(sb);
        return sb.toString();
    }

    /**
     * Build system prompt from JPA entities (kept for compatibility).
     */
    public String buildSystemPrompt(ClientCatalogueEntity catalogue) {
        StringBuilder sb = new StringBuilder();

        sb.append("You are an expert SQL analyst. Your job is to convert a plain-English question into a valid PostgreSQL query.\n\n");
        sb.append("Today's date is ").append(LocalDate.now()).append(".\n\n");
        sb.append("DATABASE SCHEMA\n");
        sb.append("==============\n");
        sb.append("Schema: ").append(catalogue.getSchemaName()).append("\n\n");

        for (CatalogueTableEntity table : catalogue.getTables()) {
            sb.append("TABLE: ").append(table.getTableSchema()).append(".").append(table.getTableName()).append("\n");
            if (table.getDescription() != null && !table.getDescription().isBlank()) {
                sb.append("  Description: ").append(table.getDescription()).append("\n");
            }
            sb.append("  Columns:\n");

            for (CatalogueColumnEntity col : table.getColumns()) {
                sb.append("    - ").append(col.getColumnName())
                  .append(" (").append(col.getDataType()).append(")");

                if (col.getDescription() != null && !col.getDescription().isBlank())
                    sb.append(" → ").append(col.getDescription());
                if (col.getRole() != null && !col.getRole().isBlank())
                    sb.append(" [role: ").append(col.getRole()).append("]");
                if (col.getSampleValues() != null && !col.getSampleValues().equals("[]")) {
                    String s = col.getSampleValues().replace("[","").replace("]","").replace("\"","");
                    sb.append(" | samples: ").append(s);
                }
                if (col.getValueMeanings() != null && !col.getValueMeanings().equals("{}")) {
                    String s = col.getValueMeanings().replace("{","").replace("}","").replace("\"","");
                    sb.append(" | meanings: ").append(s);
                }
                if (col.getSynonyms() != null && !col.getSynonyms().equals("[]")) {
                    String s = col.getSynonyms().replace("[","").replace("]","").replace("\"","");
                    sb.append(" | also called: ").append(s);
                }
                if (col.getMinValue() != null || col.getMaxValue() != null) {
                    sb.append(" | range: ").append(col.getMinValue()).append(" to ").append(col.getMaxValue());
                }
                sb.append("\n");
            }
            sb.append("\n");
        }

        appendRules(sb);
        return sb.toString();
    }

    /**
     * User prompt: the plain-English question the PM/founder asked.
     */
    public String buildUserPrompt(String question) {
        return "Question: " + question;
    }

    // ── Shared rules appended to every system prompt ─────────────────

    private void appendRules(StringBuilder sb) {
        sb.append("RULES\n");
        sb.append("=====\n");
        sb.append("1. Return ONLY a valid PostgreSQL SELECT query. No explanation, no markdown, no backticks.\n");
        sb.append("2. Always use fully qualified table names (schema.table).\n");
        sb.append("3. Use ILIKE for case-insensitive text matching where appropriate.\n");
        sb.append("4. For time-based questions, use the column identified as [role: timestamp].\n");
        sb.append("5. Always add LIMIT 1000 unless the user asks for a specific number.\n");
        sb.append("6. If the question cannot be answered from the schema, return: SELECT 'Cannot answer this question from available data' AS message;\n");
        sb.append("7. Return only the raw SQL — the first character must be 'S' (SELECT).\n");
    }
}
