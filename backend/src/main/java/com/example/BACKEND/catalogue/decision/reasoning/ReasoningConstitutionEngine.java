package com.example.BACKEND.catalogue.decision.reasoning;

import com.example.BACKEND.catalogue.decision.contracts.DecisionModels.*;
import com.example.BACKEND.catalogue.decision.reasoning.ClaimExtractor.RawClaim;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * Global Reasoning Governance Engine.
 *
 * Operates AFTER materiality ranking and BEFORE executive synthesis.
 * Produces a {@link ConstitutionReview} that governs what the LLM is
 * allowed to conclude, infer, hypothesise, or claim.
 *
 * The engine enforces globally — across ALL playbooks, ALL objectives:
 *
 *   1. No unsupported causal claims (competition, market events, external causes)
 *   2. No fabricated business context
 *   3. No external assumptions unless evidence exists
 *   4. Every conclusion maps to a specific observation
 *   5. Certainty, inference, and hypothesis are clearly separated
 *   6. Speculation is filtered before reaching the LLM
 *
 * This is NOT a prompt patch. It is a structural epistemic contract
 * enforced deterministically before any LLM call.
 */
@Service
public class ReasoningConstitutionEngine {

    private static final Logger log = LoggerFactory.getLogger(ReasoningConstitutionEngine.class);

    private static final int MAX_OBSERVATIONS  = 8;
    private static final int MAX_INFERENCES    = 5;
    private static final int MAX_HYPOTHESES    = 3;

    private final ClaimExtractor      claimExtractor;
    private final EpistemicClassifier classifier;

    public ReasoningConstitutionEngine(
            ClaimExtractor      claimExtractor,
            EpistemicClassifier classifier
    ) {
        this.claimExtractor = claimExtractor;
        this.classifier     = classifier;
    }

    /**
     * Review ranked evidence, classify all extractable claims, and
     * produce a {@link ConstitutionReview} governing synthesis behaviour.
     */
    public ConstitutionReview review(
            List<RankedEvidence> ranked,
            List<EvidenceObject> evidence,
            IntentResolution     intent
    ) {
        // Step 1: extract raw claims from evidence
        List<RawClaim> rawClaims = claimExtractor.extract(ranked, evidence);

        // Step 2: classify each claim epistemically
        List<LabeledClaim> labeled = rawClaims.stream()
                .map(classifier::classify)
                .toList();

        // Step 3: partition by epistemic label
        List<LabeledClaim> observations    = filterAndLimit(labeled, EpistemicLabel.OBSERVATION,             MAX_OBSERVATIONS);
        List<LabeledClaim> inferences      = filterAndLimit(labeled, EpistemicLabel.ANALYTICAL_INFERENCE,    MAX_INFERENCES);
        List<LabeledClaim> hypotheses      = filterAndLimit(labeled, EpistemicLabel.HYPOTHESIS,              MAX_HYPOTHESES);
        List<LabeledClaim> speculation     = filterAndLimit(labeled, EpistemicLabel.UNSUPPORTED_SPECULATION, Integer.MAX_VALUE);

        // Step 4: derive reasoning constraints from what was filtered
        List<String> constraints = buildConstraints(observations, inferences, hypotheses, speculation, intent);

        int totalReviewed = labeled.size();
        int filtered      = speculation.size();

        log.info("[constitution] runId={} observations={} inferences={} hypotheses={} filteredSpeculation={}",
                intent.runId(), observations.size(), inferences.size(), hypotheses.size(), filtered);

        return new ConstitutionReview(observations, inferences, hypotheses, speculation, constraints, totalReviewed);
    }

    // ─── constraint builder ────────────────────────────────────────────

    private List<String> buildConstraints(
            List<LabeledClaim> observations,
            List<LabeledClaim> inferences,
            List<LabeledClaim> hypotheses,
            List<LabeledClaim> filtered,
            IntentResolution   intent
    ) {
        List<String> constraints = new ArrayList<>();

        // Universal constraints — always enforced
        constraints.add("Ground every statement in the OBSERVATIONS section above. Do not state facts not listed there.");
        constraints.add("Every STRATEGIC IMPLICATION must trace explicitly to a numbered observation.");
        constraints.add("Every ACTION must address a specific, named finding in the evidence — not a generic recommendation.");
        constraints.add("Do NOT invent revenue figures, percentages, or entity names not present in the evidence.");

        // Causality constraint
        constraints.add("Do NOT claim causality (e.g. 'X caused Y'). Instead: 'X correlates with Y' or 'the pattern warrants investigation into Y'.");

        // Speculation constraints — mention what was filtered
        if (!filtered.isEmpty()) {
            constraints.add("PROHIBITED TOPICS (no evidence basis — do not mention): "
                    + summariseFiltered(filtered));
        }

        // Hypothesis framing
        if (!hypotheses.isEmpty()) {
            constraints.add("Items in HYPOTHESES section must be framed as: 'the data may suggest...', "
                    + "'this warrants further investigation into...', or 'one possible explanation is...'");
        }

        // Data coverage constraint
        if (observations.isEmpty()) {
            constraints.add("WARNING: No direct observations available. Acknowledge data limitations explicitly. Do not fabricate findings.");
        }

        // Confidence calibration
        constraints.add("Express confidence proportionally: high-magnitude observations warrant definitive statements; "
                + "inferences warrant qualified statements; hypotheses warrant explicitly uncertain framing.");

        return constraints;
    }

    private String summariseFiltered(List<LabeledClaim> filtered) {
        long specCount = filtered.stream()
                .filter(c -> c.label() == EpistemicLabel.UNSUPPORTED_SPECULATION)
                .count();
        if (specCount == 0) return "none";
        return specCount + " speculation claim(s) referencing external factors (e.g. competition, market events) — "
                + "these have NO evidence basis in the warehouse data.";
    }

    private List<LabeledClaim> filterAndLimit(List<LabeledClaim> claims, EpistemicLabel label, int limit) {
        return claims.stream()
                .filter(c -> c.label() == label)
                .limit(limit)
                .toList();
    }
}
