package com.example.BACKEND.catalogue.decision.clarification;

import com.example.BACKEND.catalogue.decision.contracts.DecisionModels.MetricDescriptor;
import com.example.BACKEND.catalogue.decision.contracts.DecisionModels.RegistryResolutionBundle;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;

/**
 * Deterministic metric fallback when the ideal metric is unavailable in the registry.
 */
@Component
public class MetricFallbackHierarchy {

    private static final List<String> HIERARCHY = List.of(
            "total_amount", "fare_amount", "tip_amount", "volume", "passenger_count", "trip_distance", "count"
    );

    public String resolve(String preferred, RegistryResolutionBundle bundle) {
        if (preferred != null && metricAvailable(preferred, bundle)) return preferred;
        for (String candidate : HIERARCHY) {
            if (metricAvailable(candidate, bundle)) return candidate;
        }
        if (bundle != null && !bundle.metrics().isEmpty()) {
            return bundle.metrics().getFirst().key();
        }
        return preferred != null ? preferred : "total_amount";
    }

    public boolean metricAvailable(String metricKey, RegistryResolutionBundle bundle) {
        if (metricKey == null || bundle == null || bundle.metrics() == null) return false;
        String norm = metricKey.toLowerCase(Locale.ROOT);
        return bundle.metrics().stream().anyMatch(m ->
                matches(m, norm));
    }

    public List<String> listAvailable(RegistryResolutionBundle bundle) {
        if (bundle == null || bundle.metrics() == null) return List.of();
        return bundle.metrics().stream().map(MetricDescriptor::key).distinct().toList();
    }

    private boolean matches(MetricDescriptor m, String norm) {
        String key = m.key() != null ? m.key().toLowerCase(Locale.ROOT) : "";
        String expr = m.expressionTemplate() != null
                ? m.expressionTemplate().toLowerCase(Locale.ROOT) : "";
        return key.contains(norm) || norm.contains(key)
                || expr.contains(norm) || key.replace("_", "").contains(norm.replace("_", ""));
    }
}
