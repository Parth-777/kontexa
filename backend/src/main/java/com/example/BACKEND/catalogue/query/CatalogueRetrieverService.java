package com.example.BACKEND.catalogue.query;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Reduces token usage by sending only relevant catalogue parts to the LLM.
 *
 * Strategy:
 *  - score each table/column against the natural-language question
 *  - keep top N tables and top M columns per table
 *  - fallback to first tables/columns if scoring has no matches
 */
@Service
public class CatalogueRetrieverService {

    private static final int MAX_TABLES = 3;
    private static final int MAX_COLUMNS_PER_TABLE = 12;
    private static final int FALLBACK_COLUMNS_PER_TABLE = 8;

    private final ObjectMapper objectMapper;

    public CatalogueRetrieverService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * Build a reduced snapshot containing only the most relevant schema parts.
     */
    public JsonNode buildRelevantCatalogueSlice(JsonNode fullSnapshot, String question) {
        Set<String> tokens = tokenize(question);
        List<TableCandidate> candidates = scoreTables(fullSnapshot.path("tables"), tokens);

        candidates.sort(Comparator.comparingInt(TableCandidate::score).reversed());

        ObjectNode reduced = objectMapper.createObjectNode();
        reduced.put("clientId", fullSnapshot.path("clientId").asText(""));
        reduced.put("schemaName", fullSnapshot.path("schemaName").asText("public"));
        reduced.put("databaseName", fullSnapshot.path("databaseName").asText(""));

        ArrayNode selectedTables = objectMapper.createArrayNode();
        int tableLimit = Math.min(MAX_TABLES, candidates.size());

        // If no candidates exist, preserve basic behavior with first tables.
        if (tableLimit == 0) {
            reduced.set("tables", selectedTables);
            return reduced;
        }

        for (int i = 0; i < tableLimit; i++) {
            selectedTables.add(candidates.get(i).tableNode());
        }

        reduced.set("tables", selectedTables);
        return reduced;
    }

    private List<TableCandidate> scoreTables(JsonNode tables, Set<String> tokens) {
        List<TableCandidate> out = new ArrayList<>();
        if (!tables.isArray()) return out;

        for (JsonNode table : tables) {
            String tableText = normalize(
                    table.path("tableName").asText("") + " "
                            + table.path("description").asText("") + " "
                            + table.path("tableSchema").asText("")
            );

            int tableBase = scoreText(tableText, tokens);

            List<ColumnCandidate> columns = scoreColumns(table.path("columns"), tokens);
            columns.sort(Comparator.comparingInt(ColumnCandidate::score).reversed());

            ObjectNode reducedTable = objectMapper.createObjectNode();
            reducedTable.put("tableName", table.path("tableName").asText(""));
            reducedTable.put("tableSchema", table.path("tableSchema").asText("public"));
            reducedTable.put("description", table.path("description").asText(""));
            reducedTable.put("rowCount", table.path("rowCount").asLong(0L));

            ArrayNode reducedColumns = objectMapper.createArrayNode();
            int picked = 0;

            // Pick scored columns first.
            for (ColumnCandidate c : columns) {
                if (picked >= MAX_COLUMNS_PER_TABLE) break;
                if (c.score() <= 0) continue;
                reducedColumns.add(c.columnNode());
                picked++;
            }

            // Fallback when nothing matched in this table.
            if (picked == 0 && table.path("columns").isArray()) {
                int fallbackCount = 0;
                for (JsonNode col : table.path("columns")) {
                    if (fallbackCount >= FALLBACK_COLUMNS_PER_TABLE) break;
                    reducedColumns.add(col);
                    fallbackCount++;
                    picked++;
                }
            }

            // Guardrail: keep at least one column if table has columns.
            if (picked == 0 && table.path("columns").isArray() && table.path("columns").size() > 0) {
                reducedColumns.add(table.path("columns").get(0));
                picked = 1;
            }

            reducedTable.set("columns", reducedColumns);

            int topColumnSignal = columns.stream()
                    .sorted(Comparator.comparingInt(ColumnCandidate::score).reversed())
                    .limit(3)
                    .mapToInt(ColumnCandidate::score)
                    .sum();

            int finalScore = tableBase + topColumnSignal;
            out.add(new TableCandidate(reducedTable, finalScore));
        }

        return out;
    }

    private List<ColumnCandidate> scoreColumns(JsonNode columns, Set<String> tokens) {
        List<ColumnCandidate> out = new ArrayList<>();
        if (!columns.isArray()) return out;

        for (JsonNode col : columns) {
            String columnText = normalize(
                    col.path("columnName").asText("") + " "
                            + col.path("description").asText("") + " "
                            + col.path("role").asText("") + " "
                            + col.path("synonyms").asText("") + " "
                            + col.path("sampleValues").asText("") + " "
                            + col.path("valueMeanings").asText("")
            );

            int score = scoreText(columnText, tokens);
            out.add(new ColumnCandidate(col, score));
        }
        return out;
    }

    private int scoreText(String haystack, Set<String> tokens) {
        if (tokens.isEmpty() || haystack.isBlank()) return 0;
        int score = 0;

        for (String token : tokens) {
            if (haystack.contains(token)) score += 2;
            if (haystack.contains(" " + token + " ")) score += 1;
        }
        return score;
    }

    private Set<String> tokenize(String text) {
        Set<String> out = new HashSet<>();
        if (text == null || text.isBlank()) return out;

        String[] parts = normalize(text).split("\\s+");
        for (String p : parts) {
            if (p.length() >= 3) out.add(p);
        }
        return out;
    }

    private String normalize(String s) {
        return s == null
                ? ""
                : s.toLowerCase()
                .replace('_', ' ')
                .replace('-', ' ')
                .replaceAll("[^a-z0-9\\s]", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }

    private record TableCandidate(ObjectNode tableNode, int score) {}

    private record ColumnCandidate(JsonNode columnNode, int score) {}
}
