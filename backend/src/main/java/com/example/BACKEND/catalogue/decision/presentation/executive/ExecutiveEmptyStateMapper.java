package com.example.BACKEND.catalogue.decision.presentation.executive;

import org.springframework.stereotype.Component;

import java.util.Locale;

/**
 * Translates internal failure reasons into business-readable empty states.
 */
@Component
public class ExecutiveEmptyStateMapper {

    public String mapRecoveryReason(String internalReason, String dimensionLabel) {
        if (internalReason == null || internalReason.isBlank()) {
            return noDataMessage();
        }
        String lower = internalReason.toLowerCase(Locale.ROOT);
        if (lower.contains("no rows") || lower.contains("no dataset")) {
            return noDataMessage();
        }
        return noDataMessage();
    }

    public String defaultMessage(String dimensionLabel) {
        return noDataMessage();
    }

    private String noDataMessage() {
        return "We could not retrieve analytical results for this question after trying all execution paths.";
    }
}
