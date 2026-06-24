package com.example.BACKEND.catalogue.decision.synthesis.answer;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Ensures the GPT synthesis user prompt carries only canonical warehouse evidence.
 */
public final class SynthesisPromptPurityChecker {

    private static final Set<String> FORBIDDEN_LEGACY_MARKERS = Set.of(
            "LEGACY_DECOY_METRIC",
            "LEGACY_DECOY_DIMENSION",
            "metric_pack__",
            "tpl__",
            "keyFindings",
            "Executive findings",
            "materialized",
            "candidate_",
            "investigation plan",
            "evidence panel",
            "Confidence score:");

    private SynthesisPromptPurityChecker() {}

    public static void assertCanonicalUserPrompt(
            String userPrompt,
            AnswerSynthesisInput input,
            ObjectMapper mapper
    ) throws Exception {
        if (userPrompt == null || userPrompt.isBlank()) {
            throw new AssertionError("user prompt is empty");
        }
        if (!userPrompt.contains("Question:")) {
            throw new AssertionError("prompt missing Question section");
        }
        if (!userPrompt.contains("Warehouse rows (source of truth):")) {
            throw new AssertionError("prompt missing warehouse rows section");
        }
        if (userPrompt.contains("SELECT ") || userPrompt.contains("select ")) {
            throw new AssertionError("prompt must not include generated SQL");
        }

        for (String marker : FORBIDDEN_LEGACY_MARKERS) {
            if (userPrompt.contains(marker)) {
                throw new AssertionError("legacy marker leaked into synthesis prompt: " + marker);
            }
        }

        String expectedRows = mapper.writerWithDefaultPrettyPrinter()
                .writeValueAsString(input.warehouseRows());
        if (!userPrompt.contains(expectedRows.trim())) {
            throw new AssertionError(
                    "prompt warehouse rows do not match synthesis input rows exactly");
        }

        if (input.metric() != null && input.metric().column() != null
                && !input.metric().column().isBlank()) {
            if (!userPrompt.contains(input.metric().column())) {
                throw new AssertionError("prompt missing canonical metric column");
            }
        }
        if (input.dimension() != null && input.dimension().column() != null
                && !input.dimension().column().isBlank()) {
            if (!userPrompt.contains(input.dimension().column())) {
                throw new AssertionError("prompt missing canonical dimension column");
            }
        }
    }

    public static void assertNoDecoyNumbers(
            String userPrompt,
            List<Map<String, Object>> decoyRows
    ) {
        for (Map<String, Object> row : decoyRows) {
            for (Object value : row.values()) {
                if (value == null) {
                    continue;
                }
                String token = value.toString();
                if (userPrompt.contains(token)) {
                    throw new AssertionError("decoy evidence leaked into prompt: " + token);
                }
            }
        }
    }
}
