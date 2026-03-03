package com.example.BACKEND.catalogue.service;

import com.example.BACKEND.catalogue.llm.OpenAiClient;
import com.example.BACKEND.catalogue.llm.PromptBuilder;
import com.example.BACKEND.catalogue.model.CatalogueResult;
import com.example.BACKEND.catalogue.model.ColumnInfo;
import com.example.BACKEND.catalogue.model.TableInfo;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * CatalogueEnrichmentService
 *
 * Takes a CatalogueResult that has been through SchemaDiscovery + DataSampling
 * and enriches every column in every table using the LLM.
 *
 * One LLM call is made per TABLE — not per column.
 * This gives the LLM full context of the whole table at once, which produces
 * much richer and more accurate descriptions.
 *
 * Example: If a table has columns "survived", "pclass", "age", "fare", "embarked"
 * the LLM immediately recognises it as the Titanic dataset and enriches accordingly.
 * If it only saw "survived" in isolation, it would have no idea.
 *
 * Works for any table from any domain:
 * - canonical_events (analytics)
 * - orders (e-commerce)
 * - titanic (Kaggle survival data)
 * - netflix_titles (Kaggle movie data)
 * - hr_data (employee records)
 * - anything else
 */
@Service
public class CatalogueEnrichmentService {

    // Pause between table API calls to avoid hitting OpenAI rate limits
    private static final long RATE_LIMIT_PAUSE_MS = 1000;

    private final OpenAiClient openAiClient;
    private final PromptBuilder promptBuilder;
    private final ObjectMapper objectMapper;

    public CatalogueEnrichmentService(OpenAiClient openAiClient,
                                       PromptBuilder promptBuilder) {
        this.openAiClient = openAiClient;
        this.promptBuilder = promptBuilder;
        this.objectMapper = new ObjectMapper();
    }

    /**
     * Main entry point.
     *
     * Enriches all tables in the catalogue using the LLM.
     * Modifies the CatalogueResult in place.
     *
     * @param catalogue  CatalogueResult from discovery + sampling
     * @return same catalogue with enrichment fields filled in
     */
    public CatalogueResult enrich(CatalogueResult catalogue) {
        String systemPrompt = promptBuilder.buildSystemPrompt();

        for (TableInfo table : catalogue.getTables()) {
            System.out.println("[Enrichment] Enriching table: " + table.getTableName()
                    + " (" + table.getColumns().size() + " columns)");

            try {
                enrichTable(table, systemPrompt);

                // Pause between tables to respect OpenAI rate limits
                if (catalogue.getTables().size() > 1) {
                    Thread.sleep(RATE_LIMIT_PAUSE_MS);
                }

            } catch (Exception e) {
                System.out.println("[Enrichment] Failed to enrich table "
                        + table.getTableName() + ": " + e.getMessage());
                // Don't fail the whole catalogue — continue to next table
            }
        }

        System.out.println("[Enrichment] Enrichment complete for all tables");
        return catalogue;
    }

    /**
     * Enriches a single table:
     * 1. Builds a user prompt describing the table and all its columns
     * 2. Sends system + user prompt to OpenAI
     * 3. Parses the JSON response
     * 4. Applies enrichment fields to each ColumnInfo
     */
    private void enrichTable(TableInfo table, String systemPrompt) throws Exception {
        // Build the user prompt for this table
        String userPrompt = promptBuilder.buildUserPrompt(table);

        // Call OpenAI
        String llmResponse = openAiClient.chat(systemPrompt, userPrompt);

        System.out.println("[Enrichment] LLM response for table '"
                + table.getTableName() + "':\n" + llmResponse);

        // Parse the JSON response
        parseAndApplyEnrichment(table, llmResponse);
    }

    /**
     * Parses the LLM JSON response and applies enrichment to each column.
     *
     * Expected LLM response format:
     * {
     *   "columns": [
     *     {
     *       "columnName": "event_name",
     *       "description": "The type of analytics event that occurred",
     *       "synonyms": ["event", "action", "what happened"],
     *       "valueMeanings": {"page_view": "User visited a page"},
     *       "role": "filter"
     *     },
     *     ...
     *   ]
     * }
     */
    private void parseAndApplyEnrichment(TableInfo table, String llmResponse) {
        try {
            JsonNode root = objectMapper.readTree(llmResponse);
            JsonNode columnsNode = root.path("columns");

            if (!columnsNode.isArray()) {
                System.out.println("[Enrichment] Warning: LLM response missing 'columns' array for "
                        + table.getTableName());
                return;
            }

            for (JsonNode colNode : columnsNode) {
                String columnName = colNode.path("columnName").asText();

                // Find the matching ColumnInfo in the table
                ColumnInfo column = table.getColumn(columnName);
                if (column == null) {
                    System.out.println("[Enrichment] Warning: LLM returned enrichment for unknown column: "
                            + columnName);
                    continue;
                }

                // Apply description
                String description = colNode.path("description").asText(null);
                if (description != null && !description.isBlank()) {
                    column.setDescription(description);
                }

                // Apply synonyms
                JsonNode synonymsNode = colNode.path("synonyms");
                if (synonymsNode.isArray()) {
                    List<String> synonyms = new ArrayList<>();
                    synonymsNode.forEach(s -> {
                        String syn = s.asText();
                        if (!syn.isBlank()) synonyms.add(syn);
                    });
                    column.setSynonyms(synonyms);
                }

                // Apply value meanings
                JsonNode valueMeaningsNode = colNode.path("valueMeanings");
                if (valueMeaningsNode.isObject()) {
                    Map<String, String> valueMeanings = new HashMap<>();
                    valueMeaningsNode.fields().forEachRemaining(entry ->
                            valueMeanings.put(entry.getKey(), entry.getValue().asText())
                    );
                    column.setValueMeanings(valueMeanings);
                }

                // Apply role
                String role = colNode.path("role").asText(null);
                if (role != null && !role.isBlank()) {
                    column.setRole(role);
                }

                column.setEnriched(true);

                System.out.println("[Enrichment] Enriched column: "
                        + table.getTableName() + "." + columnName
                        + " | role=" + role
                        + " | synonyms=" + column.getSynonyms());
            }

        } catch (Exception e) {
            System.out.println("[Enrichment] Failed to parse LLM response for table "
                    + table.getTableName() + ": " + e.getMessage());
        }
    }
}
