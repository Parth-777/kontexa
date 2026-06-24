package com.example.BACKEND.catalogue.llm;

import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Translates "logical" request parameters into model-specific OpenAI JSON fields.
 *
 * This is the compatibility layer:
 * - if the model does not support a parameter, the field is omitted entirely
 * - if a parameter name differs across model families, this maps to the correct field
 *
 * Future-proofing:
 * - new parameters can be added here with additional profile flags
 */
public class RequestParameterMapper {

    public record ChatRequestOptions(
            Double temperature,
            String responseFormatType,  // e.g. "json_object"
            Integer maxTokens,          // logical max tokens
            String reasoningEffort     // e.g. "low" | "medium" | "high"
    ) {}

    public void apply(ObjectNode body, ModelCapabilityProfile profile, ChatRequestOptions opts) {
        // temperature
        if (opts.temperature() != null && profile.supportsTemperature()) {
            body.put("temperature", opts.temperature());
        }

        // response_format
        if (opts.responseFormatType() != null && profile.supportsResponseFormat()) {
            ObjectNode fmt = body.putObject("response_format");
            fmt.put("type", opts.responseFormatType());
        }

        // max tokens: field name differs across model generations
        if (opts.maxTokens() != null) {
            if (profile.supportsMaxTokens()) {
                body.put("max_tokens", opts.maxTokens());
            } else if (profile.supportsMaxCompletionTokens()) {
                body.put("max_completion_tokens", opts.maxTokens());
            }
            // else: omit entirely
        }

        // reasoning_effort (reasoning model specific)
        if (opts.reasoningEffort() != null && profile.supportsReasoningEffort()) {
            body.put("reasoning_effort", opts.reasoningEffort());
        }
    }
}

