package com.example.BACKEND.catalogue.llm;

import com.example.BACKEND.catalogue.model.ColumnInfo;
import com.example.BACKEND.catalogue.model.TableInfo;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Constructs system/user prompts for catalogue enrichment.
 *
 * Goal: produce semantic knowledge that lets a future GPT/Claude agent
 * identify the correct field for completely unseen business questions
 * through meaning — not lexical matching of keywords or synonym lists.
 *
 * Supports full-table and leaf-batch prompts (batching is orchestration-only).
 */
@Component
public class PromptBuilder {

    private static final int MAX_USER_PHRASES = 3;

    public String buildSystemPrompt() {
        return """
                You are writing a SEMANTIC CATALOGUE that will later be the ONLY data context
                given to another AI agent (GPT/Claude).
                
                That future agent must answer novel business questions about unknown datasets.
                Users will express the same business concept in thousands of different ways.
                The agent will NOT have synonym dictionaries, playbooks, or SQL templates.
                It must reason from MEANING.
                
                Your job is to teach concepts — not to produce better short labels,
                not to enumerate keywords, and not to optimize for SQL generation.
                
                =========================================================
                PRIMARY OUTPUT: businessMeaning
                =========================================================
                
                For each usable (non-container) column, businessMeaning is the heart of the catalogue.
                Write ONE coherent semantic explanation — as if briefing a senior business analyst
                joining the company. It must naturally cover, in flowing prose (not a bullet checklist):
                
                - what the field stores
                - what business concept it represents
                - when it should be used
                - when it should NOT be used
                - how it is typically aggregated (if applicable; say "do not aggregate" when relevant)
                - the grain at which it exists (what one value / one row corresponds to)
                - important business caveats and common misunderstandings
                
                businessMeaning must stand alone: another LLM reading only that text plus the
                field name, type, and samples should pick this field for the right questions
                and reject it for the wrong ones — even for questions never seen before.
                
                Do NOT write a one-sentence gloss. Aim for a rich paragraph (or two short ones).
                
                Writing quality for businessMeaning:
                - Write naturally. Vary sentence openings. Do NOT start every explanation with
                  "Use for...", "This field should be used when...", or a rigid template.
                - Explicitly contrast this field with commonly confused siblings in the same table
                  (examples of the kind of distinctions to make when relevant: sessions vs users,
                  traffic source vs medium, pageviews vs hits, revenue vs transaction count,
                  visit id vs visitor id). Name the other field when it exists nearby.
                - State units or storage format whenever materially important for correct use
                  (e.g. currency in micros/cents, timestamps as strings vs epochs, percentages
                  as 0–1 vs 0–100). If samples imply a non-obvious scale, say so.
                - For binary / flag metrics (bounce, converted, is_*, 0/1): explain analytical
                  interpretation — what SUM/AVG/rate means, what a high or low value implies,
                  and what the flag does NOT mean — not only the literal definition.
                
                =========================================================
                SECONDARY FIELDS
                =========================================================
                
                1. description
                   A SHORT human-readable summary (one sentence). Admin UI / quick scan only.
                   Never a substitute for businessMeaning.
                
                2. synonyms (optional weak hints only)
                   At most 3 informal phrases a user might say. Prefer [] when unsure.
                   NEVER treat this as a matching dictionary or exhaustive vocabulary.
                   NEVER list 5–8 synonyms. Do not mirror the physical column name unless
                   users truly say that exact name.
                
                3. valueMeanings
                   Only if values are coded/opaque (enums, 0/1 flags, country codes).
                   Otherwise {}.
                
                4. role — exactly one:
                   dimension | metric | filter | timestamp | identifier | freetext | ignore
                   Use "ignore" for STRUCT/RECORD/OBJECT parent containers (not bindable leaves).
                
                5. aggregationMethod — for metrics: SUM | COUNT | COUNT_DISTINCT | AVG | MIN | MAX | NONE
                   For non-metrics use NONE.
                
                6. confidence — 0.0 to 1.0 how sure you are of the semantic interpretation.
                
                Also return (once per response when asked):
                - businessProfile: what this table/dataset appears to represent in business terms
                - entities: light concept map [{ "name", "description", "grain" }]
                - globalCaveats: 0–5 cross-field pitfalls for this table
                
                =========================================================
                HARD RULES
                =========================================================
                
                - Meaning is the primary signal. Do not optimize for lexical matching.
                - Do NOT invent columns. Every column listed in the user prompt MUST appear in output.
                - Do NOT invent sample values. Infer from name/type/samples/table context.
                - Do NOT emit columns that were not listed in the user prompt.
                - Poor column names: rely on sample values and neighboring columns; lower confidence.
                - Return ONLY valid JSON. No markdown fences. No prose outside JSON.
                - Use exact camelCase field names specified in the user prompt schema
                  (columnName, businessMeaning, aggregationMethod, businessProfile).
                  Do not rename them to snake_case.
                - columnName MUST match a listed physical column name.
                - businessProfile is the table-level description of THIS table.
                - Your JSON MUST be complete and closed (all braces/brackets). Never truncate.
                """;
    }

    /** Full-table prompt (all columns). */
    public String buildUserPrompt(TableInfo table) {
        return buildUserPrompt(table, table.getColumns(), true, null, null);
    }

    /**
     * Leaf-batch prompt: only the listed columns (containers are excluded by the caller).
     *
     * @param includeCatalogueLevel whether to ask for businessProfile/entities/caveats
     */
    public String buildUserPrompt(
            TableInfo table,
            List<ColumnInfo> columns,
            boolean includeCatalogueLevel,
            Integer batchNumber,
            Integer batchCount
    ) {
        StringBuilder sb = new StringBuilder();

        sb.append("Create semantic catalogue knowledge for this table.\n");
        sb.append("Teach a future AI agent the business concepts — not keyword lists.\n\n");

        if (batchNumber != null && batchCount != null) {
            sb.append("This is leaf-field batch ").append(batchNumber)
              .append(" of ").append(batchCount)
              .append(". Enrich ONLY the columns listed below. Do not invent others.\n\n");
        }

        sb.append("Table name: ").append(table.getTableName()).append("\n");
        sb.append("Schema: ").append(table.getTableSchema()).append("\n");
        if (table.getRowCount() > 0) {
            sb.append("Total rows: ").append(table.getRowCount()).append("\n");
        }
        sb.append("\n");
        sb.append("PHYSICAL COLUMNS (authoritative — do not invent fields):\n");

        int index = 1;
        for (ColumnInfo col : columns) {
            appendColumnBlock(sb, index++, col);
        }

        sb.append("\n");
        sb.append("Return JSON in this exact shape:\n");
        if (includeCatalogueLevel) {
            sb.append("""
                    {
                      "businessProfile": "string — table-level description: what THIS table represents in business terms",
                      "entities": [
                        { "name": "string", "description": "string", "grain": "string" }
                      ],
                      "globalCaveats": ["string"],
                      "columns": [
                        {
                          "columnName": "exact_column_name_from_above",
                          "description": "one-sentence short summary",
                          "businessMeaning": "rich coherent semantic explanation covering store/concept/when-to-use/when-not/aggregation/grain/caveats",
                          "synonyms": ["at most 3 optional weak hints"],
                          "valueMeanings": {},
                          "role": "dimension|metric|filter|timestamp|identifier|freetext|ignore",
                          "aggregationMethod": "SUM|COUNT|COUNT_DISTINCT|AVG|MIN|MAX|NONE",
                          "confidence": 0.0,
                          "displayUnit": "optional: currency|percent|count|number|raw",
                          "scaleToCanonical": "optional number",
                          "grainLabel": "optional short entity label"
                        }
                      ]
                    }
                    """);
        } else {
            sb.append("""
                    {
                      "columns": [
                        {
                          "columnName": "exact_column_name_from_above",
                          "description": "one-sentence short summary",
                          "businessMeaning": "rich coherent semantic explanation covering store/concept/when-to-use/when-not/aggregation/grain/caveats",
                          "synonyms": ["at most 3 optional weak hints"],
                          "valueMeanings": {},
                          "role": "dimension|metric|filter|timestamp|identifier|freetext|ignore",
                          "aggregationMethod": "SUM|COUNT|COUNT_DISTINCT|AVG|MIN|MAX|NONE",
                          "confidence": 0.0,
                          "displayUnit": "optional: currency|percent|count|number|raw",
                          "scaleToCanonical": "optional number",
                          "grainLabel": "optional short entity label"
                        }
                      ]
                    }
                    """);
        }
        sb.append("\nEnrich ALL columns listed above. Cap synonyms at ")
          .append(MAX_USER_PHRASES)
          .append(". Prefer empty synonyms over keyword padding.\n");
        sb.append("Return complete valid JSON only — every object/array must be properly closed.\n");

        return sb.toString();
    }

    private void appendColumnBlock(StringBuilder sb, int index, ColumnInfo col) {
        sb.append(index).append(". ");
        sb.append(col.getColumnName());
        sb.append(" (").append(col.getDataType()).append(")");
        if (col.isContainer()) {
            sb.append(" [CONTAINER — role must be ignore]");
        }
        if (col.getParentPath() != null && !col.getParentPath().isBlank()) {
            sb.append(" parent=").append(col.getParentPath());
        }

        if (col.getSampleValues() != null && !col.getSampleValues().isEmpty()) {
            sb.append("\n   Sample values: ")
              .append(String.join(", ", col.getSampleValues()));
        }

        if (col.getMinValue() != null && col.getMaxValue() != null
                && col.getAvgValue() != null) {
            sb.append("\n   Range: min=").append(col.getMinValue())
              .append(", max=").append(col.getMaxValue())
              .append(", avg=").append(col.getAvgValue());
        }

        if (col.getMinValue() != null && col.getMaxValue() != null
                && col.getAvgValue() == null && !col.isSkipped()) {
            sb.append("\n   Date range: ").append(col.getMinValue())
              .append(" → ").append(col.getMaxValue());
        }

        if (col.isSkipped()) {
            sb.append("\n   [No sample values — infer carefully from name, type, and table context; lower confidence]");
        }

        sb.append("\n");
    }
}
