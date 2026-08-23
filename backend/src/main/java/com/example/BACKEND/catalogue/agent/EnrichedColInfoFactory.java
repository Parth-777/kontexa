package com.example.BACKEND.catalogue.agent;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Shared parser for {@link EnrichedColInfo} from catalogue snapshot column nodes.
 */
public final class EnrichedColInfoFactory {

    private EnrichedColInfoFactory() {}

    public static EnrichedColInfo fromColumnNode(JsonNode col) {
        if (col == null || col.isMissingNode()) {
            return new EnrichedColInfo("NONE", "NONE", "N/A", "", "", null, null, null);
        }
        Double scale = null;
        if (col.has("scaleToCanonical") && !col.path("scaleToCanonical").isNull()) {
            try {
                scale = col.path("scaleToCanonical").asDouble();
            } catch (Exception ignored) {
                scale = null;
            }
        }
        String displayUnit = textOrNull(col, "displayUnit");
        String grainLabel = textOrNull(col, "grainLabel");
        return new EnrichedColInfo(
                col.path("aggregationMethod").asText("NONE"),
                col.path("comparisonPeriod").asText("NONE"),
                col.path("dateGranularity").asText("N/A"),
                col.path("businessMeaning").asText(""),
                col.path("maxValue").asText(""),
                displayUnit,
                scale,
                grainLabel
        );
    }

    private static String textOrNull(JsonNode col, String field) {
        String v = col.path(field).asText("");
        return v.isBlank() ? null : v.trim();
    }
}
