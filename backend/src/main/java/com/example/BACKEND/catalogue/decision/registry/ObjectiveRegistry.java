package com.example.BACKEND.catalogue.decision.registry;

import com.example.BACKEND.catalogue.decision.contracts.DecisionModels.*;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * Objective Registry.
 *
 * Maps analytical objective keys to their descriptors.
 * In Phase 1 this is in-memory; Phase 2 persists overrides to DB.
 */
@Component
public class ObjectiveRegistry {

    private static final Map<String, ObjectiveDescriptor> OBJECTIVES = Map.of(
            "RANKING_ANALYSIS",       new ObjectiveDescriptor("RANKING_ANALYSIS",       "ranking",      List.of("volume", "value")),
            "TREND_ANALYSIS",         new ObjectiveDescriptor("TREND_ANALYSIS",         "trend",        List.of("time_series", "growth_rate")),
            "ANOMALY_DETECTION",      new ObjectiveDescriptor("ANOMALY_DETECTION",       "anomaly",      List.of("z_score", "iqr")),
            "COMPARATIVE_ANALYSIS",   new ObjectiveDescriptor("COMPARATIVE_ANALYSIS",   "comparison",   List.of("delta", "ratio")),
            "DISTRIBUTION_ANALYSIS",  new ObjectiveDescriptor("DISTRIBUTION_ANALYSIS",  "distribution", List.of("count", "pct_share")),
            "GENERAL_ANALYSIS",       new ObjectiveDescriptor("GENERAL_ANALYSIS",       "general",      List.of("summary", "top_n"))
    );

    public ObjectiveDescriptor lookup(String objectiveKey) {
        ObjectiveDescriptor d = OBJECTIVES.get(objectiveKey);
        if (d == null) d = OBJECTIVES.get("GENERAL_ANALYSIS");
        return d;
    }

    public List<ObjectiveDescriptor> all() {
        return List.copyOf(OBJECTIVES.values());
    }
}
