package com.example.BACKEND.catalogue.decision.playbooks;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * Fallback playbook — applies when no specific playbook matches the intent.
 * Uses default ranking weights and general executive synthesis framing.
 */
@Component
public class GeneralPlaybook implements Playbook {

    @Override
    public String playbookKey() { return "GENERAL"; }

    @Override
    public String displayName() { return "General Analysis"; }

    @Override
    public String analyticalPurpose() {
        return "General-purpose analysis using default materiality scoring.";
    }

    @Override
    public boolean supports(String objectiveKey) {
        return "GENERAL_ANALYSIS".equals(objectiveKey);
    }

    @Override
    public Map<String, Double> rankingWeightOverrides() {
        return Map.of(); // empty → DefaultRankingPolicy weights apply unchanged
    }

    @Override
    public String synthesisSystemExtension() {
        return ""; // no additional framing — base template applies
    }

    @Override
    public List<String> investigationPriorities() {
        return List.of("top_signals", "segment_breakdown");
    }

    @Override
    public List<String> businessContextHints() {
        return List.of("Focus on the most material finding and its business implication.");
    }
}
