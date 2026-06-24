package com.example.BACKEND.catalogue.llm;

import com.example.BACKEND.catalogue.llm.RequestParameterMapper.ChatRequestOptions;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

/**
 * Builds the OpenAI chat-completions request JSON body using:
 * - model capability profile (what fields are supported)
 * - request parameter mapper (translation + omission)
 * - message-shaping (system role vs merged instructions)
 */
@Component
public class OpenAiRequestFactory {

    private final ObjectMapper objectMapper;
    private final RequestParameterMapper mapper;

    public OpenAiRequestFactory(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.mapper = new RequestParameterMapper();
    }

    public ObjectNode buildChatCompletionsBody(
            String model,
            ModelCapabilityProfile profile,
            String systemPrompt,
            String userPrompt
    ) {
        // logical options (actual fields are model-shaped by RequestParameterMapper)
        ChatRequestOptions opts = new ChatRequestOptions(
                0.1,          // temperature (only applied if supported)
                "json_object",
                4096,         // translated to max_tokens or max_completion_tokens
                "medium"      // reasoning_effort (only applied if supported)
        );

        ObjectNode body = objectMapper.createObjectNode();
        body.put("model", model);

        mapper.apply(body, profile, opts);

        // Messages: some reasoning models reject role="system"
        ArrayNode messages = objectMapper.createArrayNode();
        if (profile.supportsSystemRole()) {
            ObjectNode sys = objectMapper.createObjectNode();
            sys.put("role", "system");
            sys.put("content", systemPrompt);
            messages.add(sys);

            ObjectNode usr = objectMapper.createObjectNode();
            usr.put("role", "user");
            usr.put("content", userPrompt);
            messages.add(usr);
        } else {
            // Fallback: merge system instructions into the user content
            ObjectNode usr = objectMapper.createObjectNode();
            usr.put("role", "user");
            usr.put("content", systemPrompt + "\n\n" + userPrompt);
            messages.add(usr);
        }

        body.set("messages", messages);
        return body;
    }
}

