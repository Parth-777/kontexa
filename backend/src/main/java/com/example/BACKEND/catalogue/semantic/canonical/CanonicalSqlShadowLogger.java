package com.example.BACKEND.catalogue.semantic.canonical;

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

/**
 * Append-only shadow log comparing legacy SQL path vs canonical SQL path.
 */
@Component
public class CanonicalSqlShadowLogger {

    private static final Logger log = LoggerFactory.getLogger(CanonicalSqlShadowLogger.class);

    private final com.example.BACKEND.catalogue.semantic.phase2.SemanticPlanningProperties properties;
    private final ObjectMapper mapper;

    public CanonicalSqlShadowLogger(
            com.example.BACKEND.catalogue.semantic.phase2.SemanticPlanningProperties properties,
            ObjectMapper mapper
    ) {
        this.properties = properties;
        this.mapper = mapper;
    }

    public void log(ShadowEntry entry) {
        if (!properties.isCanonicalShadowEnabled()) {
            return;
        }
        try {
            ObjectNode root = mapper.createObjectNode();
            root.put("timestamp", Instant.now().toString());
            root.put("question", entry.question());
            root.set("canonicalQueryModel", mapper.valueToTree(entry.canonicalQueryModel()));
            root.put("canonicalValidationValid", entry.canonicalValidation().valid());
            root.set("canonicalValidationIssues", mapper.valueToTree(entry.canonicalValidation().issues()));
            root.put("legacySql", entry.legacySql());
            root.put("canonicalSql", entry.canonicalSql());
            root.set("canonicalFidelity", mapper.valueToTree(entry.canonicalFidelity()));
            root.set("legacyVsCanonical", mapper.valueToTree(entry.legacyVsCanonical()));
            root.put("sqlMatch", entry.sqlMatch());

            String line = mapper.writeValueAsString(root) + System.lineSeparator();
            Path path = Path.of(properties.getCanonicalSqlShadowLogPath());
            Files.createDirectories(path.getParent() != null ? path.getParent() : Path.of("."));
            Files.writeString(path, line, StandardOpenOption.CREATE, StandardOpenOption.APPEND);

            log.info("[canonical-sql-shadow] valid={} sqlMatch={} question={}",
                    entry.canonicalValidation().valid(),
                    entry.sqlMatch(),
                    truncate(entry.question(), 80));
        } catch (IOException e) {
            log.warn("[canonical-sql-shadow] failed to write log: {}", e.getMessage());
        }
    }

    private static String truncate(String value, int max) {
        if (value == null) return "";
        return value.length() <= max ? value : value.substring(0, max) + "...";
    }

    public record ShadowEntry(
            String question,
            CanonicalQueryModel canonicalQueryModel,
            CanonicalQueryValidationResult canonicalValidation,
            String legacySql,
            String canonicalSql,
            SqlFidelityReport.Result canonicalFidelity,
            SqlFidelityReport.Result legacyVsCanonical,
            boolean sqlMatch
    ) {}
}
