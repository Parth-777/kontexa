package com.example.BACKEND.catalogue.semantic.phase2;

import com.example.BACKEND.catalogue.decision.contracts.DecisionModels.QueryResult;
import com.example.BACKEND.catalogue.decision.contracts.DecisionModels.QuerySpec;
import com.example.BACKEND.catalogue.decision.planning.AnalysisPlan;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Append-only shadow comparison log for Phase-2a observability.
 */
@Component
public class GptSemanticShadowLogger {

    private static final Logger log = LoggerFactory.getLogger(GptSemanticShadowLogger.class);

    private final SemanticPlanningProperties properties;
    private final ObjectMapper mapper;

    public GptSemanticShadowLogger(SemanticPlanningProperties properties, ObjectMapper mapper) {
        this.properties = properties;
        this.mapper = mapper;
    }

    public void log(ShadowComparisonEntry entry) {
        try {
            ObjectNode root = mapper.createObjectNode();
            root.put("timestamp", Instant.now().toString());
            root.put("runId", entry.runId().toString());
            root.put("question", entry.question());
            root.set("legacyAnalysisPlan", mapper.valueToTree(entry.legacyAnalysisPlan()));
            root.set("gptStructuredPlan", mapper.valueToTree(entry.gptPlan()));
            root.put("gptValidationValid", entry.validation().valid());
            root.set("gptValidationIssues", mapper.valueToTree(entry.validation().issues()));
            root.set("legacySql", specsToJson(entry.legacySpecs()));
            root.set("gptSql", specsToJson(entry.gptSpecs()));
            root.set("legacyExecution", resultsToJson(entry.legacyResults()));
            root.set("gptExecution", resultsToJson(entry.gptResults()));
            root.put("confidence", entry.confidence());
            if (entry.error() != null) {
                root.put("error", entry.error());
            }

            String line = mapper.writeValueAsString(root) + System.lineSeparator();
            Path path = Path.of(properties.getShadowLogPath());
            Files.createDirectories(path.getParent() != null ? path.getParent() : Path.of("."));
            Files.writeString(path, line, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            log.info("[phase2-shadow] run={} confidence={} gptValid={} legacyRows={} gptRows={}",
                    entry.runId(),
                    String.format("%.2f", entry.confidence()),
                    entry.validation().valid(),
                    rowCount(entry.legacyResults()),
                    rowCount(entry.gptResults()));
        } catch (IOException e) {
            log.warn("[phase2-shadow] failed to write shadow log: {}", e.getMessage());
        }
    }

    private int rowCount(List<QueryResult> results) {
        if (results == null) return 0;
        return results.stream().mapToInt(r -> r.rows() != null ? r.rows().size() : 0).sum();
    }

    private com.fasterxml.jackson.databind.node.ArrayNode specsToJson(List<QuerySpec> specs) {
        var arr = mapper.createArrayNode();
        if (specs == null) return arr;
        for (QuerySpec s : specs) {
            var n = mapper.createObjectNode();
            n.put("key", s.key());
            n.put("sql", s.sql());
            arr.add(n);
        }
        return arr;
    }

    private com.fasterxml.jackson.databind.node.ArrayNode resultsToJson(List<QueryResult> results) {
        var arr = mapper.createArrayNode();
        if (results == null) return arr;
        for (QueryResult r : results) {
            var n = mapper.createObjectNode();
            n.put("key", r.key());
            n.put("elapsedMs", r.elapsedMs());
            n.put("rowCount", r.rows() != null ? r.rows().size() : 0);
            arr.add(n);
        }
        return arr;
    }

    public record ShadowComparisonEntry(
            UUID runId,
            String question,
            AnalysisPlan legacyAnalysisPlan,
            StructuredSemanticPlan gptPlan,
            SemanticPlanValidationResult validation,
            List<QuerySpec> legacySpecs,
            List<QuerySpec> gptSpecs,
            List<QueryResult> legacyResults,
            List<QueryResult> gptResults,
            double confidence,
            String error
    ) {}
}
