package com.example.BACKEND.catalogue.service;

import com.example.BACKEND.catalogue.llm.ChatCompletionResult;
import com.example.BACKEND.catalogue.llm.OpenAiClient;
import com.example.BACKEND.catalogue.llm.PromptBuilder;
import com.example.BACKEND.catalogue.model.CatalogueResult;
import com.example.BACKEND.catalogue.model.ColumnInfo;
import com.example.BACKEND.catalogue.model.TableInfo;
import com.example.BACKEND.catalogue.schema.NestedSchemaFieldGraph;
import com.fasterxml.jackson.core.JsonLocation;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Generate-time LLM enrichment with <b>token-budget leaf batching</b>.
 *
 * <ul>
 *   <li>Containers → lightweight local metadata (no GPT)</li>
 *   <li>Leaves → batches sized by estimated prompt + completion budgets</li>
 *   <li>Each batch must return complete JSON ({@code finish_reason=stop}); truncated
 *       responses are rejected entirely (never partially salvaged)</li>
 *   <li>Failed batches retry; persistent length failures split and re-queue</li>
 *   <li>Successful batches merge into the same {@link ColumnInfo} / {@link CatalogueResult}</li>
 * </ul>
 *
 * Persistence, approval, Phase 1/2, and planner are unchanged.
 */
@Service
public class CatalogueEnrichmentService {

    private static final long RATE_LIMIT_PAUSE_MS = 400;
    private static final int MAX_USER_PHRASES = 3;

    private final OpenAiClient openAiClient;
    private final PromptBuilder promptBuilder;
    private final ObjectMapper objectMapper;
    private final EnrichmentBatchConfig batchConfig;

    private final List<EnrichmentBatchDiagnostic> lastBatchDiagnostics = new ArrayList<>();

    /**
     * Spring injection constructor. Multiple ctors exist for tests; without
     * {@code @Autowired} Spring looks for a no-arg ctor and fails startup.
     */
    @Autowired
    public CatalogueEnrichmentService(OpenAiClient openAiClient,
                                       PromptBuilder promptBuilder) {
        this(openAiClient, promptBuilder, EnrichmentBatchConfig.defaults());
    }

    /** Test / explicit-config constructor (not used by Spring DI). */
    CatalogueEnrichmentService(OpenAiClient openAiClient,
                               PromptBuilder promptBuilder,
                               EnrichmentBatchConfig batchConfig) {
        this.openAiClient = openAiClient;
        this.promptBuilder = promptBuilder;
        this.objectMapper = new ObjectMapper();
        this.batchConfig = batchConfig != null ? batchConfig : EnrichmentBatchConfig.defaults();
    }

    public List<EnrichmentBatchDiagnostic> getLastBatchDiagnostics() {
        return List.copyOf(lastBatchDiagnostics);
    }

    public CatalogueResult enrich(CatalogueResult catalogue) {
        lastBatchDiagnostics.clear();
        String systemPrompt = promptBuilder.buildSystemPrompt();
        Path runDir = diagnosticsRunDir();

        for (TableInfo table : catalogue.getTables()) {
            System.out.println("[Enrichment] Enriching table: " + table.getTableName()
                    + " (" + table.getColumns().size() + " columns)");
            try {
                enrichTableBatched(catalogue, table, systemPrompt, runDir);
                if (catalogue.getTables().size() > 1) {
                    Thread.sleep(RATE_LIMIT_PAUSE_MS);
                }
            } catch (Exception e) {
                System.out.println("[Enrichment] Failed to enrich table "
                        + table.getTableName() + ": " + e.getMessage());
            }
        }

        writeRunSummary(runDir, catalogue);
        System.out.println("[Enrichment] Enrichment complete for all tables"
                + " | batches=" + lastBatchDiagnostics.size()
                + " | diagnosticsDir=" + runDir.toAbsolutePath());
        return catalogue;
    }

    private void enrichTableBatched(
            CatalogueResult catalogue,
            TableInfo table,
            String systemPrompt,
            Path runDir
    ) {
        List<ColumnInfo> containers = new ArrayList<>();
        List<ColumnInfo> leaves = new ArrayList<>();
        for (ColumnInfo col : table.getColumns()) {
            if (isContainer(col)) {
                containers.add(col);
            } else {
                leaves.add(col);
            }
        }

        applyLightweightContainerMetadata(containers);
        System.out.println("[Enrichment] containers=" + containers.size()
                + " (local metadata) | leaves=" + leaves.size() + " (batched GPT)");

        List<List<ColumnInfo>> planned = EnrichmentBatchPlanner.planLeafBatches(leaves, batchConfig);
        System.out.println("[Enrichment] planned leaf batches=" + planned.size()
                + " | responseBudgetTokens=" + batchConfig.safeResponseTokenBudget()
                + " | maxCompletionTokens=" + batchConfig.maxCompletionTokens());

        Deque<BatchWork> queue = new ArrayDeque<>();
        int plannedCount = planned.size();
        for (int i = 0; i < planned.size(); i++) {
            queue.add(new BatchWork(i + 1, plannedCount, planned.get(i), 0, false));
        }

        int nextSplitBatchNumber = plannedCount + 1;
        boolean catalogueLevelApplied = false;

        while (!queue.isEmpty()) {
            BatchWork work = queue.removeFirst();
            boolean includeCatalogueLevel = !catalogueLevelApplied;

            EnrichmentBatchDiagnostic diag = runLeafBatch(
                    catalogue, table, systemPrompt, work, includeCatalogueLevel, runDir);
            lastBatchDiagnostics.add(diag);

            if ("SUCCESS".equals(diag.status)) {
                if (includeCatalogueLevel) {
                    catalogueLevelApplied = true;
                }
                pauseBriefly();
                continue;
            }

            if (diag.retryCount < batchConfig.maxRetriesPerBatch()
                    && ("FAILED_HTTP".equals(diag.status)
                    || "FAILED_PARSE".equals(diag.status)
                    || "FAILED_LENGTH".equals(diag.status)
                    || "FAILED_EMPTY".equals(diag.status))) {
                System.out.println("[Enrichment] Retrying batch " + work.displayNumber()
                        + " attempt " + (work.retriesAlready + 1));
                queue.addFirst(new BatchWork(
                        work.batchIndexInPlan,
                        work.plannedBatchCount,
                        work.leaves,
                        work.retriesAlready + 1,
                        work.fromSplit));
                pauseBriefly();
                continue;
            }

            // Adaptive split when completion budget was the failure mode (or persistent parse on multi-leaf)
            if (work.leaves.size() > 1
                    && ("FAILED_LENGTH".equals(diag.status) || "FAILED_PARSE".equals(diag.status))) {
                diag.status = "SPLIT";
                List<List<ColumnInfo>> parts = EnrichmentBatchPlanner.splitBatch(work.leaves);
                System.out.println("[Enrichment] Splitting batch " + work.displayNumber()
                        + " into " + parts.size() + " smaller batches");
                for (int i = parts.size() - 1; i >= 0; i--) {
                    queue.addFirst(new BatchWork(
                            nextSplitBatchNumber++,
                            work.plannedBatchCount,
                            parts.get(i),
                            0,
                            true));
                }
                pauseBriefly();
                continue;
            }

            System.out.println("[Enrichment] Giving up on batch " + work.displayNumber()
                    + " status=" + diag.status + " error=" + diag.errorMessage);
            pauseBriefly();
        }
    }

    private EnrichmentBatchDiagnostic runLeafBatch(
            CatalogueResult catalogue,
            TableInfo table,
            String systemPrompt,
            BatchWork work,
            boolean includeCatalogueLevel,
            Path runDir
    ) {
        EnrichmentBatchDiagnostic diag = new EnrichmentBatchDiagnostic();
        diag.batchNumber = work.displayNumber();
        diag.attempt = work.retriesAlready + 1;
        diag.retryCount = work.retriesAlready;
        diag.leafCount = work.leaves.size();
        for (ColumnInfo c : work.leaves) {
            diag.leafNames.add(c.getColumnName());
        }

        int estPrompt = batchConfig.estimatedPromptOverheadTokens();
        int estCompletion = 0;
        for (ColumnInfo c : work.leaves) {
            estPrompt += EnrichmentBatchPlanner.estimateColumnPromptTokens(c);
            estCompletion += EnrichmentBatchPlanner.estimateColumnCompletionTokens(c, batchConfig);
        }
        diag.estimatedPromptTokens = estPrompt;
        diag.estimatedCompletionTokens = estCompletion;

        String userPrompt = promptBuilder.buildUserPrompt(
                table,
                work.leaves,
                includeCatalogueLevel,
                work.displayNumber(),
                Math.max(work.plannedBatchCount, work.displayNumber())
        );

        System.out.println("[Enrichment][BATCH] #" + diag.batchNumber
                + " leaves=" + diag.leafCount
                + " estPrompt~" + estPrompt
                + " estCompletion~" + estCompletion
                + " attempt=" + diag.attempt);

        ChatCompletionResult completion = openAiClient.chatDetailed(
                systemPrompt, userPrompt, batchConfig.maxCompletionTokens());

        diag.requestSent = completion.requestSent();
        diag.httpStatus = completion.httpStatus();
        diag.promptTokens = completion.promptTokens();
        diag.completionTokens = completion.completionTokens();
        diag.totalTokens = completion.totalTokens();
        diag.finishReason = completion.finishReason();
        diag.truncatedByLength = completion.truncatedByLength();
        diag.errorMessage = completion.errorMessage();

        retainRaw(runDir, "batch-" + diag.batchNumber + "-a" + diag.attempt, completion);

        if (!completion.requestSent() || completion.httpStatus() != 200) {
            diag.status = "FAILED_HTTP";
            return diag;
        }
        if (!completion.hasContent()) {
            diag.status = "FAILED_EMPTY";
            return diag;
        }
        if (completion.truncatedByLength() || "length".equalsIgnoreCase(completion.finishReason())) {
            diag.status = "FAILED_LENGTH";
            diag.errorMessage = "finish_reason=length — truncated response rejected (no partial salvage)";
            System.out.println("[Enrichment][BATCH] #" + diag.batchNumber + " REJECTED truncated response");
            return diag;
        }
        if (!"stop".equalsIgnoreCase(completion.finishReason())
                && completion.finishReason() != null
                && !completion.finishReason().isBlank()) {
            // Unusual finish reasons — treat as failure unless content parses cleanly below.
            System.out.println("[Enrichment][BATCH] #" + diag.batchNumber
                    + " unusual finish_reason=" + completion.finishReason());
        }

        try {
            JsonNode root = objectMapper.readTree(stripMarkdownFence(completion.content()));
            diag.jsonParseSucceeded = true;
            if (includeCatalogueLevel) {
                applyCatalogueLevel(catalogue, root);
            }

            JsonNode columnsNode = root.path("columns");
            if (!columnsNode.isArray()) {
                diag.status = "FAILED_PARSE";
                diag.parseExceptionMessage = "root.\"columns\" is not an array";
                diag.parseStopLocation = "columns nodeType=" + columnsNode.getNodeType();
                return diag;
            }

            diag.columnsArraySize = columnsNode.size();
            int mapped = applyColumnEnrichments(table, columnsNode);
            diag.mappedCount = mapped;

            if (mapped == 0) {
                diag.status = "FAILED_PARSE";
                diag.errorMessage = "valid JSON but zero columns mapped to ColumnInfo";
                return diag;
            }

            diag.status = "SUCCESS";
            System.out.println("[Enrichment][BATCH] #" + diag.batchNumber
                    + " SUCCESS mapped=" + mapped
                    + "/" + diag.leafCount
                    + " prompt_tokens=" + diag.promptTokens
                    + " completion_tokens=" + diag.completionTokens
                    + " finish_reason=" + diag.finishReason);
            return diag;

        } catch (Exception e) {
            diag.jsonParseSucceeded = false;
            diag.status = "FAILED_PARSE";
            diag.parseExceptionMessage = e.getMessage();
            diag.parseStopLocation = extractParseLocation(e);
            diag.errorMessage = e.getClass().getSimpleName() + ": " + e.getMessage();
            System.out.println("[Enrichment][BATCH] #" + diag.batchNumber
                    + " PARSE_FAIL " + diag.parseStopLocation);
            return diag;
        }
    }

    private int applyColumnEnrichments(TableInfo table, JsonNode columnsNode) {
        int mapped = 0;
        for (JsonNode colNode : columnsNode) {
            String columnName = colNode.path("columnName").asText();
            ColumnInfo column = table.getColumn(columnName);
            if (column == null) {
                System.out.println("[Enrichment] Warning: LLM returned enrichment for unknown column: "
                        + columnName);
                continue;
            }
            if (isContainer(column)) {
                continue; // containers are local-only
            }

            String description = textOrNull(colNode, "description");
            if (description != null) {
                column.setDescription(description);
            }

            String businessMeaning = textOrNull(colNode, "businessMeaning");
            if (businessMeaning != null) {
                column.setBusinessMeaning(businessMeaning);
            }

            JsonNode synonymsNode = colNode.path("synonyms");
            if (synonymsNode.isArray()) {
                List<String> synonyms = new ArrayList<>();
                synonymsNode.forEach(s -> {
                    String syn = s.asText();
                    if (!syn.isBlank() && synonyms.size() < MAX_USER_PHRASES) {
                        synonyms.add(syn.trim());
                    }
                });
                column.setSynonyms(synonyms);
            }

            JsonNode valueMeaningsNode = colNode.path("valueMeanings");
            if (valueMeaningsNode.isObject()) {
                Map<String, String> valueMeanings = new HashMap<>();
                valueMeaningsNode.fields().forEachRemaining(entry ->
                        valueMeanings.put(entry.getKey(), entry.getValue().asText())
                );
                column.setValueMeanings(valueMeanings);
            }

            String role = textOrNull(colNode, "role");
            if (role != null) {
                column.setRole(role.toLowerCase(Locale.ROOT));
            }

            String aggregationMethod = textOrNull(colNode, "aggregationMethod");
            if (aggregationMethod != null) {
                column.setAggregationMethod(aggregationMethod.toUpperCase(Locale.ROOT));
            }

            if (colNode.has("confidence") && !colNode.path("confidence").isNull()) {
                column.setConfidence(colNode.path("confidence").asDouble());
            }

            column.setEnriched(true);
            mapped++;
        }
        return mapped;
    }

    private void applyLightweightContainerMetadata(List<ColumnInfo> containers) {
        for (ColumnInfo column : containers) {
            column.setRole("ignore");
            column.setAggregationMethod("NONE");
            if (column.getDescription() == null || column.getDescription().isBlank()) {
                column.setDescription("Nested container; use child leaf fields instead.");
            }
            if (column.getBusinessMeaning() == null || column.getBusinessMeaning().isBlank()) {
                column.setBusinessMeaning(
                        "This field is a nested RECORD/STRUCT container, not a bindable measure or dimension. "
                                + "It groups related child fields. Do not select this path for analysis; "
                                + "choose the appropriate leaf field under this container instead.");
            }
            column.setSynonyms(List.of());
            column.setEnriched(true);
        }
    }

    private void applyCatalogueLevel(CatalogueResult catalogue, JsonNode root) {
        String profile = textOrNull(root, "businessProfile");
        if (profile != null && (catalogue.getBusinessProfile() == null
                || catalogue.getBusinessProfile().isBlank())) {
            catalogue.setBusinessProfile(profile);
        }

        JsonNode caveats = root.path("globalCaveats");
        if (caveats.isArray() && !caveats.isEmpty()) {
            List<String> merged = new ArrayList<>(catalogue.getGlobalCaveats());
            caveats.forEach(n -> {
                String c = n.asText("");
                if (!c.isBlank() && !merged.contains(c)) {
                    merged.add(c);
                }
            });
            catalogue.setGlobalCaveats(merged);
        }

        JsonNode entities = root.path("entities");
        if (entities.isArray() && !entities.isEmpty()) {
            List<Map<String, String>> merged = new ArrayList<>(catalogue.getEntities());
            for (JsonNode e : entities) {
                Map<String, String> row = new HashMap<>();
                String name = e.path("name").asText("");
                if (name.isBlank()) continue;
                row.put("name", name);
                row.put("description", e.path("description").asText(""));
                row.put("grain", e.path("grain").asText(""));
                boolean exists = merged.stream().anyMatch(m -> name.equalsIgnoreCase(m.get("name")));
                if (!exists) {
                    merged.add(row);
                }
            }
            catalogue.setEntities(merged);
        }
    }

    private static boolean isContainer(ColumnInfo col) {
        return col.isContainer()
                || NestedSchemaFieldGraph.isNestedContainerType(col.getDataType())
                || NestedSchemaFieldGraph.ROLE_CONTAINER.equalsIgnoreCase(col.getRole());
    }

    private static String textOrNull(JsonNode node, String field) {
        String v = node.path(field).asText(null);
        if (v == null || v.isBlank()) return null;
        return v.trim();
    }

    private static String stripMarkdownFence(String raw) {
        if (raw == null) return "";
        String s = raw.trim();
        if (s.startsWith("```")) {
            s = s.replaceAll("(?is)^```json\\s*", "").replaceAll("(?is)^```\\s*", "");
            s = s.replaceAll("(?is)```\\s*$", "").trim();
        }
        return s;
    }

    private static String extractParseLocation(Exception e) {
        Throwable t = e;
        while (t != null) {
            if (t instanceof JsonProcessingException jpe) {
                JsonLocation loc = jpe.getLocation();
                if (loc != null) {
                    return "line=" + loc.getLineNr()
                            + " column=" + loc.getColumnNr()
                            + " offset=" + loc.getByteOffset();
                }
                return "JsonProcessingException: " + jpe.getOriginalMessage();
            }
            t = t.getCause();
        }
        return "n/a";
    }

    private void retainRaw(Path runDir, String stem, ChatCompletionResult completion) {
        try {
            if (completion.rawResponseBody() != null) {
                Files.writeString(runDir.resolve(stem + "-raw.json"),
                        completion.rawResponseBody(), StandardCharsets.UTF_8);
            }
            if (completion.content() != null) {
                Files.writeString(runDir.resolve(stem + "-content.txt"),
                        completion.content(), StandardCharsets.UTF_8);
            }
        } catch (Exception ignored) {
            // best-effort
        }
    }

    private void pauseBriefly() {
        try {
            Thread.sleep(RATE_LIMIT_PAUSE_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private Path diagnosticsRunDir() {
        String stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
        Path dir = Path.of("target", "enrichment-diagnostics", stamp);
        try {
            Files.createDirectories(dir);
        } catch (Exception e) {
            dir = Path.of("target", "enrichment-diagnostics");
            try {
                Files.createDirectories(dir);
            } catch (Exception ignored) {
                // fall through
            }
        }
        return dir;
    }

    private void writeRunSummary(Path runDir, CatalogueResult catalogue) {
        try {
            Map<String, Object> summary = new LinkedHashMap<>();
            summary.put("databaseName", catalogue.getDatabaseName());
            summary.put("schemaName", catalogue.getSchemaName());
            summary.put("tableCount", catalogue.getTables().size());
            summary.put("totalColumns", catalogue.getTotalColumns());
            summary.put("batchConfig", Map.of(
                    "maxCompletionTokens", batchConfig.maxCompletionTokens(),
                    "responseBudgetFraction", batchConfig.responseBudgetFraction(),
                    "safeResponseTokenBudget", batchConfig.safeResponseTokenBudget(),
                    "maxPromptTokensPerBatch", batchConfig.maxPromptTokensPerBatch(),
                    "maxRetriesPerBatch", batchConfig.maxRetriesPerBatch(),
                    "estimatedCompletionTokensPerLeaf", batchConfig.estimatedCompletionTokensPerLeaf()
            ));
            summary.put("batches", lastBatchDiagnostics.stream()
                    .map(EnrichmentBatchDiagnostic::toMap).toList());
            Path out = runDir.resolve("run-summary.json");
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(out.toFile(), summary);

            for (EnrichmentBatchDiagnostic d : lastBatchDiagnostics) {
                Path bout = runDir.resolve("batch-" + d.batchNumber + "-a" + d.attempt + "-diag.json");
                objectMapper.writerWithDefaultPrettyPrinter().writeValue(bout.toFile(), d.toMap());
            }
            System.out.println("[Enrichment][DIAG] wrote " + out.toAbsolutePath());
        } catch (Exception e) {
            System.out.println("[Enrichment][DIAG] failed to write run summary: " + e.getMessage());
        }
    }

    private record BatchWork(
            int batchIndexInPlan,
            int plannedBatchCount,
            List<ColumnInfo> leaves,
            int retriesAlready,
            boolean fromSplit
    ) {
        int displayNumber() {
            return batchIndexInPlan;
        }
    }
}
