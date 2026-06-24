package com.example.BACKEND.catalogue.decision.planning;

import java.util.List;

/**
 * Defines HOW results should be compared once metrics are computed.
 *
 * The framework is built by {@link ComparativeFrameworkBuilder} from the
 * analytical intent type. It governs how {@link
 * com.example.BACKEND.catalogue.decision.evidence.ComparativeReasoningEngine}
 * should interpret and present the evidence.
 *
 * Fields:
 *   strategies          — ordered list of comparison approaches to apply
 *   normalisationNote   — how scores should be normalised for ranking
 *   rankingApproach     — the primary ranking methodology
 *   concentrationCheck  — whether to flag concentration/dependency risk
 *   framingGuidance     — how evidence should be framed in the synthesis prompt
 */
public record ComparativeFramework(
        List<ComparativeStrategy> strategies,
        String                    normalisationNote,
        String                    rankingApproach,
        boolean                   concentrationCheck,
        String                    framingGuidance
) {}
