package com.example.BACKEND.catalogue.query;

import com.example.BACKEND.catalogue.llm.OpenAiClient;
import com.example.BACKEND.catalogue.service.CatalogueApprovalService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class CatalogueQueryService {

    private final CatalogueApprovalService approvalService;
    private final CatalogueRetrieverService retrieverService;
    private final CataloguePromptBuilder promptBuilder;
    private final OpenAiClient openAiClient;
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public CatalogueQueryService(
            CatalogueApprovalService approvalService,
            CatalogueRetrieverService retrieverService,
            CataloguePromptBuilder promptBuilder,
            OpenAiClient openAiClient,
            JdbcTemplate jdbcTemplate,
            ObjectMapper objectMapper
    ) {
        this.approvalService = approvalService;
        this.retrieverService = retrieverService;
        this.promptBuilder = promptBuilder;
        this.openAiClient = openAiClient;
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    public QueryResult ask(String clientId, String question) {
        String snapshotJson = approvalService.getApprovedSnapshot(clientId);
        JsonNode fullNode = parseSnapshot(snapshotJson);
        JsonNode relevantNode = retrieverService.buildRelevantCatalogueSlice(fullNode, question);

        String systemPrompt = promptBuilder.buildSystemPromptFromSnapshot(relevantNode);
        String userPrompt = promptBuilder.buildUserPrompt(question);
        String sql = callLlmForSql(systemPrompt, userPrompt);

        sql = injectMissingCategoryFilters(sql, question, fullNode);
        sql = enforceExplicitIntentFilters(sql, question, fullNode);
        sql = normalizeSchemaQualifiers(sql, clientId, fullNode);
        sql = normalizeDatePredicates(sql, fullNode);
        sql = fixIntegerColumnILike(sql, fullNode);
        sql = fixSemanticColumnMapping(sql, question, fullNode);

        List<Map<String, Object>> rows = executeQuery(sql);
        return new QueryResult(question, sql, rows);
    }

    private String callLlmForSql(String systemPrompt, String userPrompt) {
        String augmentedSystem = systemPrompt
                + "\nIMPORTANT: Return JSON only: {\"sql\": \"<PostgreSQL SELECT query>\"}";
        String jsonResponse = openAiClient.chat(augmentedSystem, userPrompt);
        try {
            JsonNode node = objectMapper.readTree(jsonResponse);
            String sql = node.path("sql").asText();
            if (sql == null || sql.isBlank()) {
                throw new RuntimeException("LLM returned empty SQL");
            }
            return sql.trim();
        } catch (Exception e) {
            throw new RuntimeException("Failed to parse SQL from LLM response: " + jsonResponse, e);
        }
    }

    private String normalizeSchemaQualifiers(String sql, String tenantSchema, JsonNode catalogueNode) {
        if (!isFilterableSelect(sql) || tenantSchema == null || tenantSchema.isBlank()) return sql;

        Set<String> tables = new LinkedHashSet<>();
        for (JsonNode table : catalogueNode.path("tables")) {
            String tableName = table.path("tableName").asText("");
            if (!tableName.isBlank()) tables.add(tableName.toLowerCase());
        }

        Pattern ref = Pattern.compile("(?i)\\b([a-z_][a-z0-9_]*)\\.([a-z_][a-z0-9_]*)\\b");
        Matcher m = ref.matcher(sql);
        StringBuffer sb = new StringBuffer();
        boolean changed = false;

        while (m.find()) {
            String schema = m.group(1);
            String table = m.group(2);
            if (schema.equalsIgnoreCase(tenantSchema) || !tables.contains(table.toLowerCase())) {
                m.appendReplacement(sb, Matcher.quoteReplacement(m.group(0)));
                continue;
            }
            if (!tableExistsInSchema(tenantSchema, table)) {
                m.appendReplacement(sb, Matcher.quoteReplacement(m.group(0)));
                continue;
            }
            String repl = tenantSchema + "." + table;
            m.appendReplacement(sb, Matcher.quoteReplacement(repl));
            changed = true;
        }
        m.appendTail(sb);
        return changed ? sb.toString() : sql;
    }

    /**
     * Generic deterministic injection:
     * if a sample value appears in the question but is missing from SQL, inject
     * "<column> ILIKE '%sample%'" so explicit categories are preserved.
     */
    private String injectMissingCategoryFilters(String sql, String question, JsonNode catalogueNode) {
        if (!isFilterableSelect(sql)) return sql;
        String qLower = question == null ? "" : question.toLowerCase();
        String result = sql;

        for (JsonNode table : catalogueNode.path("tables")) {
            for (JsonNode col : table.path("columns")) {
                String colName = col.path("columnName").asText("");
                String samplesRaw = col.path("sampleValues").asText("[]");
                if (colName.isBlank() || samplesRaw.isBlank() || "[]".equals(samplesRaw)) continue;

                for (String sample : parseSampleValues(samplesRaw)) {
                    if (sample.length() < 3) continue;
                    String sLower = sample.toLowerCase();
                    boolean mentioned = qLower.contains(sLower)
                            || qLower.contains(sLower + "s")
                            || qLower.contains(sLower + "es");
                    if (!mentioned) continue;
                    if (result.toLowerCase().contains(sLower)) continue;
                    result = addPredicate(result, colName + " ILIKE '%" + sample + "%'");
                }
            }
        }
        return result;
    }

    /**
     * Enforces common explicit intents that LLMs may miss:
     * - "TV Show(s)" / "Movie(s)" => type filter
     * - "from <country>" => country filter
     */
    private String enforceExplicitIntentFilters(String sql, String question, JsonNode catalogueNode) {
        if (!isFilterableSelect(sql) || question == null || question.isBlank()) return sql;
        String qLower = question.toLowerCase();
        String result = sql;

        String typeCol = findLikelyColumn(catalogueNode, "type");
        if (typeCol != null) {
            boolean asksTv = qLower.contains("tv show") || qLower.contains("tv shows");
            boolean asksMovie = qLower.contains(" movie") || qLower.startsWith("movie")
                    || qLower.contains(" movies");
            if (asksTv && !result.toLowerCase().contains("tv show")) {
                result = addPredicate(result, typeCol + " ILIKE '%TV Show%'");
            } else if (asksMovie && !result.toLowerCase().contains("movie")) {
                result = addPredicate(result, typeCol + " ILIKE '%Movie%'");
            }
        }

        String countryCol = findLikelyColumn(catalogueNode, "country");
        if (countryCol != null) {
            String countryValue = extractCountryFromQuestion(question);
            if (countryValue != null && !countryValue.isBlank()) {
                String cLower = countryValue.toLowerCase();
                if (!result.toLowerCase().contains(cLower)) {
                    result = addPredicate(result, countryCol + " ILIKE '%" + countryValue + "%'");
                }
            }
        }
        return result;
    }

    private String extractCountryFromQuestion(String question) {
        String q = question.toLowerCase();

        if (q.matches(".*\\bfrom\\s+(the\\s+)?us\\b.*")) return "United States";
        if (q.matches(".*\\bfrom\\s+(the\\s+)?usa\\b.*")) return "United States";
        if (q.matches(".*\\bfrom\\s+uk\\b.*")) return "United Kingdom";
        if (q.matches(".*\\bfrom\\s+uae\\b.*")) return "United Arab Emirates";

        Matcher m = Pattern.compile("(?i)\\bfrom\\s+(?:the\\s+)?([a-z][a-z\\s]{1,30}?)(?:\\s+in\\b|\\s+released\\b|\\s+where\\b|\\?|$)")
                .matcher(question);
        if (m.find()) {
            String raw = m.group(1).trim();
            if (!raw.isBlank()) {
                return toTitleCase(raw);
            }
        }
        return null;
    }

    private String toTitleCase(String input) {
        String[] parts = input.toLowerCase().split("\\s+");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < parts.length; i++) {
            if (parts[i].isBlank()) continue;
            if (sb.length() > 0) sb.append(" ");
            sb.append(Character.toUpperCase(parts[i].charAt(0)));
            if (parts[i].length() > 1) sb.append(parts[i].substring(1));
        }
        return sb.toString();
    }

    private String findLikelyColumn(JsonNode catalogueNode, String intent) {
        String key = intent.toLowerCase();
        for (JsonNode table : catalogueNode.path("tables")) {
            for (JsonNode col : table.path("columns")) {
                String name = col.path("columnName").asText("").toLowerCase();
                String desc = col.path("description").asText("").toLowerCase();
                if (name.contains(key) || desc.contains(key)) {
                    return col.path("columnName").asText("");
                }
            }
        }
        return null;
    }

    private String addPredicate(String sql, String predicate) {
        if (!isFilterableSelect(sql)) return sql;

        String lower = sql.toLowerCase();
        int limitIdx = lower.indexOf(" limit ");
        int orderIdx = lower.indexOf(" order by ");
        int groupIdx = lower.indexOf(" group by ");

        int cut = -1;
        for (int idx : new int[]{limitIdx, orderIdx, groupIdx}) {
            if (idx >= 0 && (cut == -1 || idx < cut)) cut = idx;
        }

        String head = cut == -1 ? sql : sql.substring(0, cut);
        String tail = cut == -1 ? "" : sql.substring(cut);
        if (head.toLowerCase().contains(" where ")) {
            head = head + " AND " + predicate;
        } else {
            head = head + " WHERE " + predicate;
        }
        return head + tail;
    }

    private List<String> parseSampleValues(String raw) {
        List<String> out = new ArrayList<>();
        if (raw == null || raw.isBlank() || "[]".equals(raw)) return out;
        try {
            JsonNode node = objectMapper.readTree(raw);
            if (node.isArray()) {
                for (JsonNode v : node) {
                    String s = v.asText("").trim();
                    if (!s.isBlank()) out.add(s);
                }
                return out;
            }
        } catch (Exception ignored) {
        }
        String cleaned = raw.replace("[", "").replace("]", "").replace("\"", "");
        for (String token : cleaned.split(",")) {
            String t = token.trim();
            if (!t.isBlank()) out.add(t);
        }
        return out;
    }

    private String normalizeDatePredicates(String sql, JsonNode catalogueNode) {
        if (!isFilterableSelect(sql)) return sql;
        String normalized = sql;

        for (JsonNode table : catalogueNode.path("tables")) {
            for (JsonNode col : table.path("columns")) {
                String colName = col.path("columnName").asText("");
                String dataType = col.path("dataType").asText("").toLowerCase();
                if (colName.isBlank() || !isDateType(dataType)) continue;

                Pattern p = Pattern.compile("(?i)((?:\\w+\\.){0,2})" + Pattern.quote(colName)
                        + "\\s+ilike\\s+'%([0-9]{4})%'");
                Matcher m = p.matcher(normalized);
                StringBuffer sb = new StringBuffer();
                boolean replaced = false;
                while (m.find()) {
                    String full = m.group(1) + colName;
                    String year = m.group(2);
                    m.appendReplacement(sb, Matcher.quoteReplacement("EXTRACT(YEAR FROM " + full + ") = " + year));
                    replaced = true;
                }
                m.appendTail(sb);
                if (replaced) normalized = sb.toString();
            }
        }
        return normalized;
    }

    private String fixIntegerColumnILike(String sql, JsonNode catalogueNode) {
        if (!isFilterableSelect(sql)) return sql;
        String normalized = sql;
        for (JsonNode table : catalogueNode.path("tables")) {
            for (JsonNode col : table.path("columns")) {
                String colName = col.path("columnName").asText("");
                String dataType = col.path("dataType").asText("").toLowerCase();
                if (colName.isBlank() || !isNumericType(dataType)) continue;
                Pattern p = Pattern.compile("(?i)\\b" + Pattern.quote(colName) + "\\s+ilike\\s+'%([0-9]+)%'");
                Matcher m = p.matcher(normalized);
                StringBuffer sb = new StringBuffer();
                boolean replaced = false;
                while (m.find()) {
                    m.appendReplacement(sb, Matcher.quoteReplacement(colName + " = " + m.group(1)));
                    replaced = true;
                }
                m.appendTail(sb);
                if (replaced) normalized = sb.toString();
            }
        }
        return normalized;
    }

    private String fixSemanticColumnMapping(String sql, String question, JsonNode catalogueNode) {
        if (!isFilterableSelect(sql) || question == null) return sql;
        String qLower = question.toLowerCase();
        boolean releaseIntent = qLower.contains("released in") || qLower.contains("came out in")
                || qLower.contains("release year") || qLower.contains("premiered in");
        if (!releaseIntent) return sql;

        Matcher y = Pattern.compile("\\b(19[0-9]{2}|20[0-9]{2})\\b").matcher(question);
        if (!y.find()) return sql;
        String year = y.group(1);

        String releaseYearCol = null;
        for (JsonNode table : catalogueNode.path("tables")) {
            for (JsonNode col : table.path("columns")) {
                String cName = col.path("columnName").asText("").toLowerCase();
                String dtype = col.path("dataType").asText("").toLowerCase();
                if (isNumericType(dtype) && cName.contains("release") && cName.contains("year")) {
                    releaseYearCol = col.path("columnName").asText("");
                    break;
                }
            }
            if (releaseYearCol != null) break;
        }
        if (releaseYearCol == null) return sql;

        Pattern wrong = Pattern.compile("(?i)\\b([a-z][a-z0-9_]*)\\s+ilike\\s+'%" + Pattern.quote(year) + "%'");
        Matcher m = wrong.matcher(sql);
        StringBuffer sb = new StringBuffer();
        boolean changed = false;
        while (m.find()) {
            String used = m.group(1).toLowerCase();
            if (used.contains("added") || used.contains("listed") || used.contains("date")) {
                m.appendReplacement(sb, Matcher.quoteReplacement(releaseYearCol + " = " + year));
                changed = true;
            } else {
                m.appendReplacement(sb, Matcher.quoteReplacement(m.group(0)));
            }
        }
        m.appendTail(sb);
        return changed ? sb.toString() : sql;
    }

    private boolean tableExistsInSchema(String schema, String table) {
        try {
            Boolean exists = jdbcTemplate.queryForObject(
                    "SELECT EXISTS (SELECT 1 FROM information_schema.tables WHERE table_schema = ? AND table_name = ?)",
                    Boolean.class, schema, table
            );
            return Boolean.TRUE.equals(exists);
        } catch (Exception ignored) {
            return false;
        }
    }

    private boolean isDateType(String dt) {
        return dt.contains("date") || dt.contains("timestamp");
    }

    private boolean isNumericType(String dt) {
        return dt.contains("int") || dt.contains("numeric") || dt.contains("decimal")
                || dt.contains("float") || dt.contains("double") || dt.contains("real");
    }

    private boolean isFilterableSelect(String sql) {
        if (sql == null) return false;
        String lower = sql.toLowerCase().trim();
        return lower.startsWith("select") && lower.contains(" from ")
                && !lower.contains("cannot answer this question from available data");
    }

    private JsonNode parseSnapshot(String json) {
        try {
            return objectMapper.readTree(json);
        } catch (Exception e) {
            throw new RuntimeException("Failed to parse snapshot JSON: " + e.getMessage(), e);
        }
    }

    private List<Map<String, Object>> executeQuery(String sql) {
        try {
            return jdbcTemplate.query(sql, (rs, rowNum) -> {
                Map<String, Object> row = new LinkedHashMap<>();
                int cols = rs.getMetaData().getColumnCount();
                for (int i = 1; i <= cols; i++) {
                    row.put(rs.getMetaData().getColumnLabel(i), rs.getObject(i));
                }
                return row;
            });
        } catch (Exception e) {
            throw new RuntimeException("SQL execution failed.\nSQL: " + sql + "\nError: " + e.getMessage(), e);
        }
    }

    public static class QueryResult {
        private final String question;
        private final String generatedSql;
        private final List<Map<String, Object>> rows;
        private final int rowCount;

        public QueryResult(String question, String generatedSql, List<Map<String, Object>> rows) {
            this.question = question;
            this.generatedSql = generatedSql;
            this.rows = rows != null ? rows : new ArrayList<>();
            this.rowCount = this.rows.size();
        }

        public String getQuestion() { return question; }
        public String getGeneratedSql() { return generatedSql; }
        public List<Map<String, Object>> getRows() { return rows; }
        public int getRowCount() { return rowCount; }
    }
}
