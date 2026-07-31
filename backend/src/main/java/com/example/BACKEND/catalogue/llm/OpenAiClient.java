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

    public String getModel() {
        return model;
    }

    /**
     * Sends a chat completion request shaped to the active model's capabilities.
     */
    public String chat(String systemPrompt, String userPrompt) {
        return chat(systemPrompt, userPrompt, 4096);
    }

    /**
     * @param maxTokens completion budget (catalogue enrichment needs a larger budget
     *                  for rich businessMeaning prose across many columns)
     */
    public String chat(String systemPrompt, String userPrompt, int maxTokens) {
        ChatCompletionResult result = chatDetailed(systemPrompt, userPrompt, maxTokens);
        if (!result.hasContent()) {
            throw new RuntimeException(
                    result.errorMessage() != null
                            ? result.errorMessage()
                            : "OpenAI returned empty content | finish_reason=" + result.finishReason()
                            + " | http=" + result.httpStatus());
        }
        return result.content();
    }

    /**
     * Diagnostic-capable chat: always returns metadata even on HTTP/content failure.
     * Does not throw for HTTP errors — callers inspect {@link ChatCompletionResult}.
     */
    public ChatCompletionResult chatDetailed(String systemPrompt, String userPrompt, int maxTokens) {
        try {
            ObjectNode body = requestFactory.buildChatCompletionsBody(
                    model, profile, systemPrompt, userPrompt, maxTokens);
            String requestJson = objectMapper.writeValueAsString(body);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(OPENAI_URL))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + apiKey)
                    .POST(HttpRequest.BodyPublishers.ofString(requestJson))
                    .timeout(Duration.ofSeconds(300))
                    .build();

            System.out.println("[OpenAiClient] Calling OpenAI | model: " + model
                    + " | maxTokens=" + maxTokens
                    + " | requestJsonChars=" + requestJson.length());

            HttpResponse<String> response = httpClient.send(
                    request, HttpResponse.BodyHandlers.ofString());

            int status = response.statusCode();
            String rawBody = response.body() != null ? response.body() : "";

            if (status != 200) {
                System.out.println("[OpenAiClient] HTTP error " + status
                        + " | bodyChars=" + rawBody.length()
                        + " | bodyPreview=" + preview(rawBody, 800));
                return new ChatCompletionResult(
                        true, status, model, maxTokens,
                        null, null, null, null, null,
                        rawBody,
                        "OpenAI API error: HTTP " + status + " | body: " + preview(rawBody, 2000)
                );
            }

            JsonNode json = objectMapper.readTree(rawBody);
            JsonNode choice0 = json.path("choices").path(0);
            String content = choice0.path("message").path("content").asText(null);
            String finishReason = choice0.path("finish_reason").asText(null);
            JsonNode usage = json.path("usage");
            Integer promptTokens = usage.path("prompt_tokens").isMissingNode()
                    ? null : usage.path("prompt_tokens").asInt();
            Integer completionTokens = usage.path("completion_tokens").isMissingNode()
                    ? null : usage.path("completion_tokens").asInt();
            Integer totalTokens = usage.path("total_tokens").isMissingNode()
                    ? null : usage.path("total_tokens").asInt();

            System.out.println("[OpenAiClient] Response OK"
                    + " | finish_reason=" + finishReason
                    + " | contentChars=" + (content == null ? 0 : content.length())
                    + " | prompt_tokens=" + promptTokens
                    + " | completion_tokens=" + completionTokens
                    + " | total_tokens=" + totalTokens);

            return new ChatCompletionResult(
                    true, status, model, maxTokens,
                    content, finishReason,
                    promptTokens, completionTokens, totalTokens,
                    rawBody,
                    null
            );

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new ChatCompletionResult(
                    false, -1, model, maxTokens,
                    null, null, null, null, null, null,
                    "OpenAI request interrupted: " + e.getMessage()
            );
        } catch (Exception e) {
            return new ChatCompletionResult(
                    false, -1, model, maxTokens,
                    null, null, null, null, null, null,
                    "OpenAI API call failed: " + e.getMessage()
            );
        }
    }

    private static String preview(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }
}
