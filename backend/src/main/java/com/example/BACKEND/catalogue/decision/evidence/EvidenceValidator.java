package com.example.BACKEND.catalogue.decision.evidence;

import com.example.BACKEND.catalogue.decision.contracts.DecisionModels.EvidenceObject;
import org.springframework.stereotype.Component;

/**
 * Validates that an {@link EvidenceObject} is fit for ranking and synthesis.
 *
 * An evidence object is valid when:
 *   - it has a non-blank entity reference
 *   - it has at least one metric, comparison, or comparative context
 *   - its confidence is above the minimum threshold
 */
@Component
public class EvidenceValidator {

    private static final double MIN_CONFIDENCE = 0.05;

    public boolean isValid(EvidenceObject evidence) {
        if (evidence == null) return false;
        if (evidence.entityRef() == null || evidence.entityRef().isBlank()) return false;
        if (evidence.confidence() < MIN_CONFIDENCE) return false;
        boolean hasData = !evidence.metrics().isEmpty()
                || !evidence.comparisons().isEmpty()
                || !evidence.comparativeContexts().isEmpty();
        return hasData;
    }
}
