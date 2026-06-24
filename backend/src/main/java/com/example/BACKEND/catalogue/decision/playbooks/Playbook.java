package com.example.BACKEND.catalogue.decision.playbooks;

import java.util.List;
import java.util.Map;

/**
 * An Executive Analytical Playbook.
 *
 * A playbook is deterministic analytical orchestration — NOT an agent.
 * It configures how the existing decision runtime reasons for a specific
 * analytical objective (strategic value, revenue drivers, anomalies, etc.).
 *
 * Each playbook controls three things:
 *   1. RANKING  — which materiality features matter most for THIS objective
 *   2. SYNTHESIS — what the LLM should focus on and how to frame output
 *   3. INVESTIGATION — which dimensions to drill into first
 *
 * Adding a new analytical capability = adding one new Playbook bean.
 */
public interface Playbook {

    /** Unique identifier. Used by PlaybookRouter to select the right playbook. */
    String playbookKey();

    /** Human-readable name displayed in logs and API responses. */
    String displayName();

    /** One-sentence description of what this playbook measures. */
    String analyticalPurpose();

    /** Returns true if this playbook handles the given objective key. */
    boolean supports(String objectiveKey);

    /**
     * Feature weight overrides for the materiality ranking engine.
     *
     * Keys must match those produced by {@link com.example.BACKEND.catalogue.decision.ranking.FeatureExtractor}.
     * Any key NOT listed here falls back to the default policy weight.
     * Weights do NOT need to sum to 1.0 — they are normalised at scoring time.
     */
    Map<String, Double> rankingWeightOverrides();

    /**
     * Playbook-specific additions to the LLM system prompt.
     * Injected AFTER the base executive synthesis template.
     * Must be concise (≤ 200 words) — this is appended to a tight token budget.
     */
    String synthesisSystemExtension();

    /**
     * Ordered list of analytical dimensions this playbook investigates first.
     * Guides the InvestigationTreeBuilder on which segments to surface.
     */
    List<String> investigationPriorities();

    /**
     * Business context hints passed to the LLM in the evidence prompt.
     * Frame the analytical lens: what this playbook typically reveals.
     */
    List<String> businessContextHints();
}
