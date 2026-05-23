package com.example.BACKEND.catalogue.query;

import com.example.BACKEND.catalogue.entity.CatalogueColumnEntity;
import com.example.BACKEND.catalogue.entity.CatalogueTableEntity;
import com.example.BACKEND.catalogue.entity.ClientCatalogueEntity;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Builds LLM prompts for NLP-to-SQL conversion.
 *
 * Key improvement: every column now includes:
 *   - actual sampleValues from the catalogue (so LLM knows TRUE/FALSE vs yes/no, etc.)
 *   - businessMeaning for semantic grounding
 *   - role (metric / dimension / timestamp / identifier)
 *
 * This prevents the #1 failure mode: LLM guessing wrong filter values
 * (e.g. is_returning_customer LIKE '%yes%' when the real values are TRUE/FALSE).
 */
@Component
public class CataloguePromptBuilder {

    public String buildSystemPromptFromSnapshot(JsonNode catalogueNode) {
        StringBuilder sb = new StringBuilder();
        sb.append("You are an expert SQL analyst. Convert the user question into a valid SQL SELECT query.\n\n");
        sb.append("Today's date: ").append(LocalDate.now()).append("\n\n");
        sb.append("DATABASE SCHEMA\n===============\n");
        sb.append("Schema: ").append(catalogueNode.path("schemaName").asText("public")).append("\n\n");

        for (JsonNode table : catalogueNode.path("tables")) {
            String tableSchema = table.path("tableSchema").asText("public");
            String tableName   = table.path("tableName").asText("");
            sb.append("TABLE: ").append(tableSchema).append(".").append(tableName).append("\n");

            String tableDesc = table.path("description").asText("");
            if (!tableDesc.isBlank())
                sb.append("  Description: ").append(tableDesc).append("\n");

            sb.append("  Columns:\n");
            for (JsonNode col : table.path("columns")) {
                appendColumn(sb, col);
            }
            sb.append("\n");
        }

        appendRules(sb);
        return sb.toString();
    }

    public String buildSystemPrompt(ClientCatalogueEntity catalogue) {
        StringBuilder sb = new StringBuilder();
        sb.append("You are an expert SQL analyst. Convert the user question into a valid SQL SELECT query.\n\n");
        sb.append("Today's date: ").append(LocalDate.now()).append("\n\n");
        sb.append("Schema: ").append(catalogue.getSchemaName()).append("\n\n");

        for (CatalogueTableEntity table : catalogue.getTables()) {
            sb.append("TABLE: ").append(table.getTableSchema()).append(".").append(table.getTableName()).append("\n");
            if (table.getDescription() != null && !table.getDescription().isBlank())
                sb.append("  Description: ").append(table.getDescription()).append("\n");

            sb.append("  Columns:\n");
            for (CatalogueColumnEntity col : table.getColumns()) {
                sb.append("    - ").append(col.getColumnName())
                  .append(" (").append(col.getDataType()).append(")");
                if (col.getDescription() != null && !col.getDescription().isBlank())
                    sb.append(" → ").append(col.getDescription());
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

    // ── Column renderer with sample values ────────────────────────────────────

    private void appendColumn(StringBuilder sb, JsonNode col) {
        String colName      = col.path("columnName").asText("");
        String dataType     = col.path("dataType").asText("?");
        String meaning      = col.path("businessMeaning").asText(
                              col.path("description").asText(""));
        String role         = col.path("role").asText("");
        String samplesRaw   = col.path("sampleValues").asText("[]");

        sb.append("    - ").append(colName).append(" (").append(dataType).append(")");

        if (!meaning.isBlank())
            sb.append(" → ").append(meaning);

        if (!role.isBlank())
            sb.append(" [").append(role).append("]");

        // ── Sample values — most important for preventing wrong filter values ──
        List<String> samples = parseSampleValues(samplesRaw);
        if (!samples.isEmpty()) {
            // Detect boolean-like columns
            boolean isBool = samples.stream().allMatch(s ->
                    s.equalsIgnoreCase("true") || s.equalsIgnoreCase("false") ||
                    s.equals("0") || s.equals("1") || s.equals("t") || s.equals("f"));

            if (isBool) {
                sb.append(" ⚠ BOOLEAN — use = TRUE or = FALSE (never LIKE)");
            } else {
                // Show up to 6 sample values so LLM picks exact casing/spelling
                int show = Math.min(samples.size(), 6);
                sb.append(" — sample values: [");
                for (int i = 0; i < show; i++) {
                    if (i > 0) sb.append(", ");
                    sb.append("'").append(samples.get(i)).append("'");
                }
                if (samples.size() > show) sb.append(", ...");
                sb.append("]");
            }
        }

        sb.append("\n");
    }

    private List<String> parseSampleValues(String raw) {
        List<String> result = new ArrayList<>();
        if (raw == null || raw.isBlank() || raw.equals("[]") || raw.equals("null")) return result;
        try {
            // Handle JSON array format: ["val1","val2"] or plain comma list
            String trimmed = raw.trim();
            if (trimmed.startsWith("[")) {
                trimmed = trimmed.substring(1);
                if (trimmed.endsWith("]")) trimmed = trimmed.substring(0, trimmed.length() - 1);
            }
            for (String part : trimmed.split(",")) {
                String v = part.trim().replaceAll("^\"|\"$", "").replaceAll("^'|'$", "").trim();
                if (!v.isEmpty()) result.add(v);
            }
        } catch (Exception ignored) {}
        return result;
    }

    // ── SQL generation rules ──────────────────────────────────────────────────

    private void appendRules(StringBuilder sb) {
        sb.append("""
                SQL GENERATION RULES
                ====================
                1.  Return ONLY the SQL SELECT statement. No explanation, no markdown fences, no comments.

                2.  Use fully qualified schema.table names exactly as shown in the schema above.

                3.  BOOLEAN COLUMNS: Columns marked ⚠ BOOLEAN must use = TRUE or = FALSE.
                    NEVER use LIKE or ILIKE on boolean columns.
                    Example: WHERE is_returning_customer = TRUE

                4.  TEXT COLUMNS: Use ILIKE '%value%' for partial matches. Use = 'value' for exact matches.
                    Always use the exact casing shown in the sample values list above.
                    If sample values are shown, use them — do not invent values.

                5.  INTEGER/NUMERIC COLUMNS: Use =, >, <, BETWEEN. Never use ILIKE.
                    Example: WHERE discount_pct = 20 (not ILIKE '%20%')

                6.  DATE/TIMESTAMP COLUMNS: Use date functions or BETWEEN for range filters.
                    TEXT date columns: use ILIKE '%2024%' for year-only filters.
                    INTEGER year columns (e.g. release_year): use = 2024 directly.

                7.  ALWAYS add LIMIT 1000 unless a different limit is specified.

                8.  If the question truly cannot be answered by any column in the schema,
                    return: SELECT 'Cannot answer this question from available data' AS message;

                9.  Never invent column names. Use only columns listed in the schema above.

                10. If a question asks about a concept (e.g. "returning customers"), look for
                    a boolean/flag column that represents it (e.g. is_returning_customer = TRUE)
                    rather than filtering on a name or text field.

                11. For aggregation questions (how many, total, average, most common):
                    use COUNT(*), SUM(), AVG(), GROUP BY as appropriate.
                    Always alias aggregate columns with meaningful names.

                12. For TREND / OVER TIME / PAST YEARS questions:
                    GROUP BY the year or period column (e.g. EXTRACT(YEAR FROM date_col), or year column).
                    ORDER BY period ascending. Return one row per time period.
                    Example: "trend of employee count over past years" →
                    SELECT year_col, SUM(employee_count) AS total FROM schema.table
                    GROUP BY year_col ORDER BY year_col;
                """);
    }
}
