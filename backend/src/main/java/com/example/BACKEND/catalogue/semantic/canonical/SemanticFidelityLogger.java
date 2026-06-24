package com.example.BACKEND.catalogue.semantic.canonical;

import com.example.BACKEND.catalogue.decision.planning.AnalysisPlan;
import com.example.BACKEND.catalogue.semantic.phase2.SemanticPlanningProperties;
import com.example.BACKEND.catalogue.semantic.phase2.StructuredSemanticPlan;
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
 * Append-only observability log: GPT plan, faithful canonical model, and mutating analysis plan side-by-side.
 */
@Component
public class SemanticFidelityLogger {

    private static final Logger log = LoggerFactory.getLogger(SemanticFidelityLogger.class);

    private final SemanticPlanningProperties properties;
    private final ObjectMapper mapper;

    public SemanticFidelityLogger(SemanticPlanningProperties properties, ObjectMapper mapper) {
        this.properties = properties;
        this.mapper = mapper;
    }

    public void log(FidelityLogEntry entry) {
        try {
            ObjectNode root = mapper.createObjectNode();
            root.put("timestamp", Instant.now().toString());
            if (entry.question() != null) {
                root.put("question", entry.question());
            }
            root.set("structuredSemanticPlan", mapper.valueToTree(entry.structuredSemanticPlan()));
            root.set("canonicalQueryModel", mapper.valueToTree(entry.canonicalQueryModel()));
            root.set("analysisPlan", mapper.valueToTree(entry.analysisPlan()));
            root.set("fidelity", mapper.valueToTree(entry.fidelity()));

            String line = mapper.writeValueAsString(root) + System.lineSeparator();
            Path path = Path.of(properties.getFidelityLogPath());
            Files.createDirectories(path.getParent() != null ? path.getParent() : Path.of("."));
            Files.writeString(path, line, StandardOpenOption.CREATE, StandardOpenOption.APPEND);

            log.info("[semantic-fidelity] measure={} agg={} partition={} limit={} relationship={} timeGrain={} mutations={}",
                    entry.fidelity().measurePreserved(),
                    entry.fidelity().aggregationPreserved(),
                    entry.fidelity().partitionPreserved(),
                    entry.fidelity().limitPreserved(),
                    entry.fidelity().relationshipOperandsPreserved(),
                    entry.fidelity().timeGrainPreserved(),
                    entry.fidelity().mutations().size());
        } catch (IOException e) {
            log.warn("[semantic-fidelity] failed to write fidelity log: {}", e.getMessage());
        }
    }

    public record FidelityLogEntry(
            String question,
            StructuredSemanticPlan structuredSemanticPlan,
            CanonicalQueryModel canonicalQueryModel,
            AnalysisPlan analysisPlan,
            SemanticFidelityReport.Result fidelity
    ) {}
}
