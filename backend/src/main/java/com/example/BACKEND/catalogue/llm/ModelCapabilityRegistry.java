package com.example.BACKEND.catalogue.llm;

import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Maps OpenAI model identifiers to their {@link ModelCapabilities}.
 *
 * Matching is done by prefix/substring scan (longest-match wins), so a
 * single entry covers all snapshots of a model family without needing to
 * enumerate every dated version.
 *
 * To add a new model family: add one entry to the REGISTRY map.
 * {@link OpenAiClient} requires no changes.
 *
 * Entries are evaluated in insertion order; the first matching prefix wins.
 * Put more specific prefixes before more general ones.
 *
 * ┌────────────────────────┬────────────────────────────────────────────────┐
 * │ Model prefix           │ Capability profile                             │
 * ├────────────────────────┼────────────────────────────────────────────────┤
 * │ o1, o3                 │ reasoning — no temp, no response_format,       │
 * │                        │ no system role, uses max_completion_tokens      │
 * ├────────────────────────┼────────────────────────────────────────────────┤
 * │ gpt-5, gpt-5.5         │ gpt5Style — no custom temp, uses max_completion_tokens │
 * ├────────────────────────┼────────────────────────────────────────────────┤
 * │ gpt-4, gpt-4o          │ fullCapability — all params supported           │
 * ├────────────────────────┼────────────────────────────────────────────────┤
 * │ gpt-3.5                │ fullCapability                                  │
 * ├────────────────────────┼────────────────────────────────────────────────┤
 * │ (fallback)             │ minimal — safest possible request               │
 * └────────────────────────┴────────────────────────────────────────────────┘
 */
@Component
public class ModelCapabilityRegistry {

    // Ordered: most-specific prefixes first
    private static final Map<String, ModelCapabilityProfile> REGISTRY = new LinkedHashMap<>();

    static {
        // Reasoning model families — no temperature, no response_format, no system role
        REGISTRY.put("o1",           ModelCapabilityProfile.reasoningModel());
        REGISTRY.put("o3",           ModelCapabilityProfile.reasoningModel());
        REGISTRY.put("o4",           ModelCapabilityProfile.reasoningModel());

        // GPT-5.x family — no custom temperature
        REGISTRY.put("gpt-5",        ModelCapabilityProfile.gpt5Style());

        // GPT-4 family — full capabilities
        REGISTRY.put("gpt-4",        ModelCapabilityProfile.fullCapability());
        REGISTRY.put("gpt-3.5",      ModelCapabilityProfile.fullCapability());

        // Generic catch-all for any future model not yet listed
        // Will be superseded when a specific prefix is added above
        REGISTRY.put("gpt",          ModelCapabilityProfile.gpt5Style()); // assume newer = restrictive
    }

    /**
     * Returns the {@link ModelCapabilities} for the given model identifier.
     *
     * Matching: iterates REGISTRY in insertion order; returns the capabilities
     * of the first prefix that is found (case-insensitive) inside the model name.
     * Falls back to {@link ModelCapabilities#minimal()} if nothing matches.
     *
     * @param modelId  the value of {@code openai.model} from application.properties
     */
    public ModelCapabilityProfile capabilitiesFor(String modelId) {
        if (modelId == null || modelId.isBlank()) return ModelCapabilityProfile.minimal();
        String lower = modelId.toLowerCase();
        for (Map.Entry<String, ModelCapabilityProfile> entry : REGISTRY.entrySet()) {
            if (lower.contains(entry.getKey().toLowerCase())) {
                return entry.getValue();
            }
        }
        return ModelCapabilityProfile.minimal();
    }
}
