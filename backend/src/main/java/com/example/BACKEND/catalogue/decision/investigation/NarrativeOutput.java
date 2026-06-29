package com.example.BACKEND.catalogue.decision.investigation;

import java.util.List;

/**
 * The "why" explanation synthesized from an {@link EvidencePack}.
 */
public record NarrativeOutput(
        String executiveSummary,
        List<String> keyFindings,
        String confidenceExplanation,
        List<String> followUpQuestions,
        boolean fromLlm
) {}
