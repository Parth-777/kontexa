package com.example.BACKEND.catalogue.semantic.phase2;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * OpenAI structured JSON completion for Phase-2 semantic planning.
 */
@Component
public class GptStructuredCompletionClient {

    private static final String URL = "https://api.openai.com/v1/chat/completions";

    private final String apiKey;
    private final String model;
    private final ObjectMapper mapper;
    private final HttpClient http;

    public GptStructuredCompletionClient(
            @Value("${openai.api.key}") String apiKey,
            @Value("${openai.model}") String model,
            ObjectMapper mapper
    ) {
        this.apiKey = apiKey;
        this.model = model != null && !model.isBlank() ? model : "gpt-4o-mini";
        this.mapper = mapper;
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(30)).build();
    }

    public String completeStructured(String systemPrompt, String userPrompt, JsonNode jsonSchema) {
        return completeStructured(systemPrompt, userPrompt, jsonSchema, "phase2_semantic_plan");
    }

    public String completeStructured(
            String systemPrompt, String userPrompt, JsonNode jsonSchema, String schemaName
    ) {
        int[] backoffMs = {0, 2000, 5000};
        RuntimeException last = null;
        for (int wait : backoffMs) {
            if (wait > 0) sleep(wait);
            try {
                return doRequest(systemPrompt, userPrompt, jsonSchema, schemaName);
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
        return doRequest(systemPrompt, userPrompt, jsonSchema, "phase2_semantic_plan");
    }

    private String doRequest(
            String systemPrompt, String userPrompt, JsonNode jsonSchema, String schemaName
    ) {
        try {
            ObjectNode body = mapper.createObjectNode();
            body.put("model", model);
            body.put("temperature", 0.0);

            ObjectNode responseFormat = mapper.createObjectNode();
            responseFormat.put("type", "json_schema");
            ObjectNode schemaWrapper = mapper.createObjectNode();
            schemaWrapper.put("name", schemaName != null && !schemaName.isBlank()
                    ? schemaName : "structured_response");
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
            throw new RuntimeException("Phase-2 OpenAI call failed: " + e.getMessage(), e);
        }
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
