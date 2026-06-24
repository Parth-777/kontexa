package com.example.BACKEND.catalogue.decision.reasoning;

import com.example.BACKEND.catalogue.decision.contracts.DecisionModels.*;
import com.example.BACKEND.catalogue.decision.reasoning.ClaimExtractor.ClaimSource;
import com.example.BACKEND.catalogue.decision.reasoning.ClaimExtractor.RawClaim;
import org.springframework.stereotype.Component;

/**
 * Classifies raw claims into the four epistemic categories.
 *
 * Classification rules:
 *
 *   OBSERVATION              — derived directly from a computed metric value
 *                              or a comparative context (warehouse-computed facts).
 *                              State as fact. No hedging.
 *
 *   ANALYTICAL_INFERENCE     — a conclusion drawn from comparative pattern evidence
 *                              (e.g. "concentration risk" derived from distribution data).
 *                              State as conclusion: "evidence indicates", "data shows".
 *
 *   HYPOTHESIS               — an interpretation that proposes an explanation but
 *                              cannot be proven from current evidence alone.
 *                              Frame with: "may suggest", "warrants investigation".
 *
 *   UNSUPPORTED_SPECULATION  — references external factors with no evidence basis
 *                              (competitors, market events, customer psychology).
 *                              FILTERED OUT — never sent to LLM.
 *
 * The classifier is deterministic — it does NOT call an LLM.
 * It classifies purely based on claim source and textual markers.
 */
@Component
public class EpistemicClassifier {

    // Phrases that signal a claim is reasoning beyond evidence
    private static final String[] SPECULATION_MARKERS = {
            "competition", "competitor", "rival", "market trend", "economic", "macr",
            "customer sentiment", "customer psychology", "brand perception", "industry shift",
            "regulatory", "geopolitical", "inflation", "pandemic", "supply chain"
    };

    // Phrases that signal the claim is already hedged as a hypothesis
    private static final String[] HYPOTHESIS_MARKERS = {
            "may ", "might ", "could ", "possibly", "perhaps", "suggests that",
            "likely due", "probable cause", "potential", "it appears", "seems to"
    };

    // Phrases that signal an analytical inference (conclusion from data)
    private static final String[] INFERENCE_MARKERS = {
            "concentration", "risk", "indicates", "demonstrates", "shows that",
            "outperform", "underperform", "bottleneck", "dependency", "dominat",
            "critical", "significant", "material", "accounts for", "drives"
    };

    public LabeledClaim classify(RawClaim raw) {
        String text    = raw.claimText();
        String textLow = text.toLowerCase();

        // Rule 1: speculation — filtered, never reaches LLM
        if (containsAny(textLow, SPECULATION_MARKERS)) {
            return new LabeledClaim(text, EpistemicLabel.UNSUPPORTED_SPECULATION,
                    raw.magnitude(), raw.evidenceRef(),
                    "References external factors not present in warehouse evidence.");
        }

        // Rule 2: metric values and comparative contexts are observations
        if (raw.source() == ClaimSource.METRIC_VALUE
                || raw.source() == ClaimSource.COMPARATIVE_CONTEXT) {
            return new LabeledClaim(text, EpistemicLabel.OBSERVATION,
                    raw.magnitude(), raw.evidenceRef(),
                    "Directly computed from warehouse execution.");
        }

        // Rule 3: investigation findings (segment data) are observations
        if (raw.source() == ClaimSource.INVESTIGATION_FINDING) {
            return new LabeledClaim(text, EpistemicLabel.OBSERVATION,
                    raw.magnitude(), raw.evidenceRef(),
                    "Derived from segment breakdown of computed results.");
        }

        // Rule 4: interpretation text — sub-classify
        if (raw.source() == ClaimSource.INVESTIGATION_INTERPRETATION) {
            if (containsAny(textLow, SPECULATION_MARKERS)) {
                return new LabeledClaim(text, EpistemicLabel.UNSUPPORTED_SPECULATION,
                        raw.magnitude(), raw.evidenceRef(),
                        "Interpretation references external factors without evidence.");
            }
            if (containsAny(textLow, HYPOTHESIS_MARKERS)) {
                return new LabeledClaim(text, EpistemicLabel.HYPOTHESIS,
                        raw.magnitude(), raw.evidenceRef(),
                        "Interpretation uses hedging language — not directly proven.");
            }
            if (containsAny(textLow, INFERENCE_MARKERS)) {
                return new LabeledClaim(text, EpistemicLabel.ANALYTICAL_INFERENCE,
                        raw.magnitude(), raw.evidenceRef(),
                        "Conclusion drawn from comparative segment pattern.");
            }
            // Default for interpretations without clear markers
            return new LabeledClaim(text, EpistemicLabel.HYPOTHESIS,
                    raw.magnitude(), raw.evidenceRef(),
                    "Interpretation without direct evidence basis — treated as hypothesis.");
        }

        // Fallback
        return new LabeledClaim(text, EpistemicLabel.OBSERVATION,
                raw.magnitude(), raw.evidenceRef(), "Default classification.");
    }

    private boolean containsAny(String text, String[] markers) {
        for (String m : markers) {
            if (text.contains(m)) return true;
        }
        return false;
    }
}
