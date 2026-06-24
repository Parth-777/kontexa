package com.example.BACKEND.experiment.phase1;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * OpenAI chat completions with JSON-schema structured output.
 * Lives in the experiment package — does not modify {@code OpenAiClient}.
 */
public final class Phase1OpenAiStructuredClient implements Phase1LlmClient {

    private static final String URL = "https://api.openai.com/v1/chat/completions";

    private final String apiKey;
    private final String model;
    private final ObjectMapper mapper;
    private final HttpClient http;

    public Phase1OpenAiStructuredClient(String apiKey, String model, ObjectMapper mapper) {
        this.apiKey = apiKey;
        this.model = model != null && !model.isBlank() ? model : "gpt-4o-mini";
        this.mapper = mapper;
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(30)).build();
    }

    @Override
    public String completeStructured(String systemPrompt, String userPrompt, JsonNode jsonSchema) {
        int[] backoffMs = {0, 2000, 5000, 15000};
        RuntimeException last = null;
        for (int attempt = 0; attempt < backoffMs.length; attempt++) {
            if (backoffMs[attempt] > 0) {
                try {
                    Thread.sleep(backoffMs[attempt]);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException("OpenAI retry interrupted", e);
                }
            }
            try {
                return doRequest(systemPrompt, userPrompt, jsonSchema);
            } catch (RuntimeException e) {
                last = e;
                String msg = e.getMessage() != null ? e.getMessage() : "";
                if (!msg.contains("HTTP 429") && !msg.contains("HTTP 5")) {
                    throw e;
                }
            }
        }
        throw last != null ? last : new RuntimeException("OpenAI request failed after retries");
    }

    private String doRequest(String systemPrompt, String userPrompt, JsonNode jsonSchema) {
        try {
            ObjectNode body = mapper.createObjectNode();
            body.put("model", model);
            body.put("temperature", 0.0);

            ObjectNode responseFormat = mapper.createObjectNode();
            responseFormat.put("type", "json_schema");
            ObjectNode schemaWrapper = mapper.createObjectNode();
            schemaWrapper.put("name", "phase1_query_plan");
            schemaWrapper.put("strict", true);
            schemaWrapper.set("schema", jsonSchema);
            responseFormat.set("json_schema", schemaWrapper);
            body.set("response_format", responseFormat);

            ArrayNode messages = mapper.createArrayNode();
            ObjectNode sys = mapper.createObjectNode();
            sys.put("role", "system");
            sys.put("content", systemPrompt);
            messages.add(sys);
            ObjectNode usr = mapper.createObjectNode();
            usr.put("role", "user");
            usr.put("content", userPrompt);
            messages.add(usr);
            body.set("messages", messages);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(URL))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + apiKey)
                    .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)))
                    .timeout(Duration.ofSeconds(120))
                    .build();

            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new RuntimeException("OpenAI HTTP " + response.statusCode() + ": " + response.body());
            }
            JsonNode json = mapper.readTree(response.body());
            return json.path("choices").path(0).path("message").path("content").asText();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("OpenAI interrupted", e);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("Phase-1 OpenAI call failed: " + e.getMessage(), e);
        }
    }
}
