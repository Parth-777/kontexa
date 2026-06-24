package com.example.BACKEND.catalogue.llm;

/**
 * Declares which OpenAI chat-completions request fields are supported by a model family.
 *
 * The key compatibility rule:
 * Unsupported fields MUST be omitted entirely from the JSON request body
 * (not set to a default), because many models reject unknown/unsupported parameters
 * with HTTP 400.
 */
public record ModelCapabilityProfile(
        boolean supportsTemperature,
        boolean supportsResponseFormat,
        boolean supportsMaxTokens,
        boolean supportsMaxCompletionTokens,
        boolean supportsSystemRole,
        boolean supportsReasoningEffort,
        boolean jsonOutputReliable
) {

    /** GPT-4 / GPT-4o class defaults. */
    public static ModelCapabilityProfile fullCapability() {
        return new ModelCapabilityProfile(
                true,  // temperature
                true,  // response_format
                true,  // max_tokens
                false, // max_completion_tokens
                true,  // system role
                false, // reasoning_effort
                true   // json output can be forced
        );
    }

    /**
     * GPT-5.x family behavior (including GPT-5.5):
     * - rejects custom temperature
     * - rejects max_tokens, requires max_completion_tokens
     */
    public static ModelCapabilityProfile gpt5Style() {
        return new ModelCapabilityProfile(
                false, // temperature
                true,  // response_format (assume supported; omitted if not)
                false, // max_tokens
                true,  // max_completion_tokens
                true,  // system role
                false, // reasoning_effort
                true
        );
    }

    /** o1/o3 class reasoning models. */
    public static ModelCapabilityProfile reasoningModel() {
        return new ModelCapabilityProfile(
                false, // temperature
                false, // response_format
                false, // max_tokens
                true,  // max_completion_tokens
                false, // no system role
                true,  // reasoning_effort
                true
        );
    }

    /** Minimal safe fallback (omit optional fields). */
    public static ModelCapabilityProfile minimal() {
        return new ModelCapabilityProfile(
                false, // temperature
                false, // response_format
                false, // max_tokens
                false, // max_completion_tokens
                true,  // system role (default to standard; most models accept it)
                false, // reasoning_effort
                false
        );
    }
}

