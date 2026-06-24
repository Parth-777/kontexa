package com.example.BACKEND.experiment.phase1;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Isolated LLM port for Phase-1 experiments (not wired into production runtime).
 */
public interface Phase1LlmClient {

    /**
     * @return raw JSON string matching the Phase-1 structured response schema
     */
    String completeStructured(String systemPrompt, String userPrompt, JsonNode jsonSchema);
}
