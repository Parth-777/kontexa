package com.example.BACKEND.catalogue.query;

import com.example.BACKEND.catalogue.entity.CatalogueColumnEntity;
import com.example.BACKEND.catalogue.entity.CatalogueTableEntity;
import com.example.BACKEND.catalogue.entity.ClientCatalogueEntity;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;

import java.time.LocalDate;

@Component
public class CataloguePromptBuilder {

    public String buildSystemPromptFromSnapshot(JsonNode catalogueNode) {
        StringBuilder sb = new StringBuilder();
        sb.append("You are an expert SQL analyst. Convert user questions into valid PostgreSQL SELECT SQL.\n\n");
        sb.append("Today's date is ").append(LocalDate.now()).append(".\n\n");
        sb.append("DATABASE SCHEMA\n==============\n");
        sb.append("Schema: ").append(catalogueNode.path("schemaName").asText("public")).append("\n\n");

        for (JsonNode table : catalogueNode.path("tables")) {
            String tableSchema = table.path("tableSchema").asText("public");
            String tableName = table.path("tableName").asText("");
            sb.append("TABLE: ").append(tableSchema).append(".").append(tableName).append("\n");
            if (!table.path("description").asText("").isBlank()) {
                sb.append("  Description: ").append(table.path("description").asText("")).append("\n");
            }
            sb.append("  Columns:\n");
            for (JsonNode col : table.path("columns")) {
                String colName = col.path("columnName").asText("");
                String dataType = col.path("dataType").asText("");
                String desc = col.path("description").asText("");
                String role = col.path("role").asText("");
                sb.append("    - ").append(colName).append(" (").append(dataType).append(")");
                if (!desc.isBlank()) sb.append(" -> ").append(desc);
                if (!role.isBlank()) sb.append(" [role: ").append(role).append("]");
                sb.append("\n");
            }
            sb.append("\n");
        }

        appendRules(sb);
        return sb.toString();
    }

    public String buildSystemPrompt(ClientCatalogueEntity catalogue) {
        StringBuilder sb = new StringBuilder();
        sb.append("You are an expert SQL analyst. Convert user questions into valid PostgreSQL SELECT SQL.\n\n");
        sb.append("Today's date is ").append(LocalDate.now()).append(".\n\n");
        sb.append("Schema: ").append(catalogue.getSchemaName()).append("\n\n");
        for (CatalogueTableEntity table : catalogue.getTables()) {
            sb.append("TABLE: ").append(table.getTableSchema()).append(".").append(table.getTableName()).append("\n");
            if (table.getDescription() != null && !table.getDescription().isBlank()) {
                sb.append("  Description: ").append(table.getDescription()).append("\n");
            }
            sb.append("  Columns:\n");
            for (CatalogueColumnEntity col : table.getColumns()) {
                sb.append("    - ").append(col.getColumnName()).append(" (").append(col.getDataType()).append(")");
                if (col.getDescription() != null && !col.getDescription().isBlank()) {
                    sb.append(" -> ").append(col.getDescription());
                }
                sb.append("\n");
            }
            sb.append("\n");
        }
        appendRules(sb);
        return sb.toString();
    }

    public String buildUserPrompt(String question) {
        return "Question: " + question;
    }

    private void appendRules(StringBuilder sb) {
        sb.append("RULES\n=====\n");
        sb.append("1. Return ONLY PostgreSQL SELECT SQL. No explanation, no markdown.\n");
        sb.append("2. Use fully qualified schema.table names.\n");
        sb.append("3. Use ILIKE '%value%' for text filtering.\n");
        sb.append("4. For TEXT date columns, use ILIKE '%2020%' style year filters.\n");
        sb.append("5. For DATE/TIMESTAMP columns, use date functions or BETWEEN.\n");
        sb.append("6. Always add LIMIT 1000 unless user asks different limit.\n");
        sb.append("7. If question cannot be answered, return: SELECT 'Cannot answer this question from available data' AS message;\n");
        sb.append("8. Semantic mapping is critical: 'released in YEAR' should target release/production year columns, not date_added columns.\n");
        sb.append("9. Integer year columns must use equals, e.g. release_year = 2020 (not ILIKE).\n");
    }
}
