package com.example.BACKEND.catalogue.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Capability-aware OpenAI Chat Completions client.
 *
 * Refactored into capability/profile + request parameter translation + request factory.
 */
@Component
public class OpenAiClient {

    private static final String OPENAI_URL = "https://api.openai.com/v1/chat/completions";

    private final String                  apiKey;
    private final String                  model;
    private final ObjectMapper            objectMapper;
    private final HttpClient              httpClient;
    private final ModelCapabilityProfile profile;
    private final OpenAiRequestFactory   requestFactory;

    public OpenAiClient(
            @Value("${openai.api.key}") String apiKey,
            @Value("${openai.model}")   String model,
            ObjectMapper                objectMapper,
            ModelCapabilityRegistry     registry,
            OpenAiRequestFactory        requestFactory
    ) {
        this.apiKey       = apiKey;
        this.model        = model;
        this.objectMapper = objectMapper;
        this.profile = registry.capabilitiesFor(model);
        this.requestFactory = requestFactory;
        this.httpClient   = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(30))
                .build();

        System.out.printf("[OpenAiClient] model=%s caps={temp=%b responseFormat=%b systemRole=%b reasoning=%b}%n",
                model,
                profile.supportsTemperature(),
                profile.supportsResponseFormat(),
                profile.supportsSystemRole(),
                profile.supportsReasoningEffort());
    }

    /**
     * Sends a chat completion request shaped to the active model's capabilities.
     *
     * @param systemPrompt  Role/instruction context
     * @param userPrompt    The analytical question or evidence payload
     * @return              Raw text content from the model's response
     */
    public String chat(String systemPrompt, String userPrompt) {
        try {
            ObjectNode body = requestFactory.buildChatCompletionsBody(model, profile, systemPrompt, userPrompt);
            String requestJson = objectMapper.writeValueAsString(body);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(OPENAI_URL))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + apiKey)
                    .POST(HttpRequest.BodyPublishers.ofString(requestJson))
                    .timeout(Duration.ofSeconds(120))
                    .build();

            System.out.println("[OpenAiClient] Calling OpenAI | model: " + model);

            HttpResponse<String> response = httpClient.send(
                    request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                throw new RuntimeException(
                        "OpenAI API error: HTTP " + response.statusCode()
                                + " | body: " + response.body());
            }

            JsonNode json = objectMapper.readTree(response.body());
            String content = json.path("choices").path(0)
                    .path("message").path("content").asText();

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
