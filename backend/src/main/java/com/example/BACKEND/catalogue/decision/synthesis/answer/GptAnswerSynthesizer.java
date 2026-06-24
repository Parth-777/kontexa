package com.example.BACKEND.catalogue.decision.synthesis.answer;

import com.example.BACKEND.catalogue.semantic.phase2.GptStructuredCompletionClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * GPT-backed answer synthesis from warehouse rows only.
 */
@Component
public class GptAnswerSynthesizer {

    private static final Logger log = LoggerFactory.getLogger(GptAnswerSynthesizer.class);
    private static final int MAX_FINDINGS = 5;
    private static final int EVIDENCE_ROW_SAMPLE = 5;

    private final GptStructuredCompletionClient completionClient;
    private final ObjectMapper mapper;

    public GptAnswerSynthesizer(GptStructuredCompletionClient completionClient, ObjectMapper mapper) {
        this.completionClient = completionClient;
        this.mapper = mapper;
    }

    public AnswerSynthesisOutput synthesize(AnswerSynthesisInput input) {
        if (input == null || !input.hasWarehouseRows()) {
            log.warn("[answer-synthesis-evidence] SKIPPED — input null or warehouseRows empty | inputNull={} rowCount={}",
                    input == null,
                    input != null && input.warehouseRows() != null ? input.warehouseRows().size() : -1);
            return AnswerSynthesisOutput.empty();
        }

        String system = AnswerSynthesisPrompt.systemPrompt();
        String user = AnswerSynthesisPrompt.userPrompt(input, mapper);
        JsonNode schema = AnswerSynthesisPrompt.responseSchema(mapper);

        logSynthesisEvidence(input, system, user);

        String json = completionClient.completeStructured(
                system, user, schema, "warehouse_answer_synthesis");

        log.info("[answer-synthesis-evidence] GPT_RAW_RESPONSE={}", json);

        return parse(json);
    }

    private void logSynthesisEvidence(AnswerSynthesisInput input, String systemPrompt, String userPrompt) {
        List<Map<String, Object>> rows = input.warehouseRows();
        int rowCount = rows != null ? rows.size() : 0;
        List<Map<String, Object>> sample = rowCount == 0
                ? List.of()
                : List.copyOf(rows.subList(0, Math.min(EVIDENCE_ROW_SAMPLE, rowCount)));

        try {
            log.info("[answer-synthesis-evidence] warehouseRows.size={}", rowCount);
            log.info("[answer-synthesis-evidence] warehouseRows.first{}={}",
                    EVIDENCE_ROW_SAMPLE, mapper.writeValueAsString(sample));
            log.info("[answer-synthesis-evidence] canonicalQueryModel={}",
                    input.canonicalQueryModel() != null
                            ? mapper.writeValueAsString(input.canonicalQueryModel())
                            : "null");
            log.info("[answer-synthesis-evidence] answerSynthesisInput={}",
                    mapper.writeValueAsString(input));
            log.info("[answer-synthesis-evidence] GPT_SYSTEM_PROMPT={}", systemPrompt);
            log.info("[answer-synthesis-evidence] GPT_USER_PROMPT={}", userPrompt);
        } catch (Exception e) {
            log.warn("[answer-synthesis-evidence] serialization failed: {}", e.getMessage());
        }
    }

    private AnswerSynthesisOutput parse(String json) {
        try {
            JsonNode node = mapper.readTree(json);
            List<String> findings = new ArrayList<>();
            if (node.path("keyFindings").isArray()) {
                node.path("keyFindings").forEach(f -> {
                    if (findings.size() >= MAX_FINDINGS) return;
                    String text = f.asText("").trim();
                    if (!text.isBlank()) findings.add(text);
                });
            }
            List<String> followUps = new ArrayList<>();
            if (node.path("followUpQuestions").isArray()) {
                node.path("followUpQuestions").forEach(f -> {
                    if (followUps.size() >= 3) return;
                    String text = f.asText("").trim();
                    if (!text.isBlank()) followUps.add(text);
                });
            }
            return new AnswerSynthesisOutput(
                    node.path("executiveSummary").asText("").trim(),
                    List.copyOf(findings),
                    node.path("confidenceExplanation").asText("").trim(),
                    node.path("suggestedVisualization").asText("NONE").trim(),
                    node.path("answerType").asText("OTHER").trim(),
                    List.copyOf(followUps));
        } catch (Exception e) {
            log.warn("[answer-synthesis] parse failed: {}", e.getMessage());
            return AnswerSynthesisOutput.empty();
        }
    }
}
