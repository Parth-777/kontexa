package com.example.BACKEND.catalogue.llm;

/**
 * Declares which OpenAI API request parameters a specific model supports.
 *
 * Consumed by {@link OpenAiClient} to shape each request — fields that are
 * not supported by the model are omitted entirely from the request body
 * rather than set to a default value, because some models reject unknown or
 * unsupported parameter combinations with HTTP 400.
 *
 * Design principles:
 *   - New models are registered in {@link ModelCapabilityRegistry}; no
 *     changes to {@link OpenAiClient} are needed.
 *   - All fields default to the most conservative (safe) option.
 *   - "Supported" means the API actively accepts the parameter; "not
 *     supported" means the parameter should be omitted from the request body.
 *
 * Parameters tracked:
 *
 *   supportsTemperature
 *     Whether the model accepts a custom `temperature` field.
 *     Models like GPT-5.5 only accept the default and reject any explicit value.
 *     When false, the field is omitted entirely.
 *
 *   supportsResponseFormat
 *     Whether `response_format: {type: "json_object"}` is accepted.
 *     Older snapshots and reasoning-only models may not support this.
 *
 *   supportsMaxTokens
 *     Whether `max_tokens` is the correct field name.
 *     Some reasoning models use `max_completion_tokens` instead.
 *
 *   supportsSystemRole
 *     Whether a `{role: "system"}` message is accepted.
 *     Some reasoning models (o1 series) only accept `user` role messages.
 *
 *   supportsReasoningEffort
 *     Whether the `reasoning_effort` field (low/medium/high) is accepted.
 *     Specific to o1-series and future reasoning-augmented models.
 *
 *   jsonOutputReliable
 *     Whether JSON-only output can be expected without explicit response_format.
 *     Used as a fallback signal when supportsResponseFormat is false.
 */
public record ModelCapabilities(
        boolean supportsTemperature,
        boolean supportsResponseFormat,
        boolean supportsMaxTokens,
        boolean supportsSystemRole,
        boolean supportsReasoningEffort,
        boolean jsonOutputReliable
) {

    /** Full-capability profile for GPT-4 / GPT-4o class models. */
    public static ModelCapabilities fullCapability() {
        return new ModelCapabilities(true, true, true, true, false, true);
    }

    /** Profile for GPT-5.x series: no custom temperature, otherwise standard. */
    public static ModelCapabilities gpt5Style() {
        return new ModelCapabilities(false, true, true, true, false, true);
    }

    /** Profile for o1/o3 reasoning models. */
    public static ModelCapabilities reasoningModel() {
        return new ModelCapabilities(false, false, false, false, true, true);
    }

    /** Minimal safe profile — omit all optional parameters. */
    public static ModelCapabilities minimal() {
        return new ModelCapabilities(false, false, false, true, false, false);
    }
}
