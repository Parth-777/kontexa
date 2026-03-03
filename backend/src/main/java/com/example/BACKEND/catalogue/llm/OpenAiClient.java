package com.example.BACKEND.catalogue.llm;

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
 * OpenAiClient
 *
 * Makes HTTP calls to the OpenAI Chat Completions API.
 * Uses Java's built-in HttpClient — no external SDK or library needed.
 *
 * All fields are injected by Spring:
 *  - apiKey and model come from application.properties
 *  - ObjectMapper is the Spring-managed Jackson bean
 *  - HttpClient is built once at construction time
 */
@Component
public class OpenAiClient {

    private static final String OPENAI_URL = "https://api.openai.com/v1/chat/completions";

    private final String apiKey;
    private final String model;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    public OpenAiClient(
            @Value("${openai.api.key}") String apiKey,
            @Value("${openai.model}") String model,
            ObjectMapper objectMapper
    ) {
        this.apiKey = apiKey;
        this.model = model;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(30))
                .build();
    }

    /**
     * Sends a chat completion request to OpenAI.
     *
     * @param systemPrompt  The system role instructions (generic, same for all tables)
     * @param userPrompt    The user message (table + column details for this specific table)
     * @return              Raw JSON string returned by the LLM
     */
    public String chat(String systemPrompt, String userPrompt) {
        try {
            // Build request body
            ObjectNode requestBody = objectMapper.createObjectNode();
            requestBody.put("model", model);
            requestBody.put("temperature", 0.1);

            // Force JSON-only response — prevents the LLM from adding prose around the JSON
            ObjectNode responseFormat = objectMapper.createObjectNode();
            responseFormat.put("type", "json_object");
            requestBody.set("response_format", responseFormat);

            // Build messages array
            ArrayNode messages = objectMapper.createArrayNode();

            ObjectNode systemMessage = objectMapper.createObjectNode();
            systemMessage.put("role", "system");
            systemMessage.put("content", systemPrompt);
            messages.add(systemMessage);

            ObjectNode userMessage = objectMapper.createObjectNode();
            userMessage.put("role", "user");
            userMessage.put("content", userPrompt);
            messages.add(userMessage);

            requestBody.set("messages", messages);

            String requestBodyStr = objectMapper.writeValueAsString(requestBody);

            // Build and send HTTP request
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(OPENAI_URL))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + apiKey)
                    .POST(HttpRequest.BodyPublishers.ofString(requestBodyStr))
                    .timeout(Duration.ofSeconds(60))
                    .build();

            System.out.println("[OpenAiClient] Calling OpenAI | model: " + model);

            HttpResponse<String> response = httpClient.send(
                    request, HttpResponse.BodyHandlers.ofString()
            );

            if (response.statusCode() != 200) {
                throw new RuntimeException(
                        "OpenAI API error: HTTP " + response.statusCode()
                                + " | body: " + response.body()
                );
            }

            // Extract content from response
            JsonNode responseJson = objectMapper.readTree(response.body());
            String content = responseJson
                    .path("choices")
                    .path(0)
                    .path("message")
                    .path("content")
                    .asText();

            System.out.println("[OpenAiClient] Response received successfully");
            return content;

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("OpenAI request interrupted", e);
        } catch (Exception e) {
            throw new RuntimeException("OpenAI API call failed: " + e.getMessage(), e);
        }
    }
}
