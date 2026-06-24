package com.example.BACKEND.catalogue.decision.registry;

import com.example.BACKEND.catalogue.decision.contracts.DecisionModels.*;
import com.example.BACKEND.catalogue.decision.registry.adapter.CatalogueMetricAdapter;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Metric Registry.
 *
 * Returns the set of {@link MetricDescriptor}s that are relevant for a
 * given analytical objective on a given tenant's schema.
 *
 * Phase 1: adapter delegates to the existing catalogue enrichment data.
 * Later phases can persist overrides in a dedicated metrics table.
 */
@Component
public class MetricRegistry {

    private final CatalogueMetricAdapter metricAdapter;

    public MetricRegistry(CatalogueMetricAdapter metricAdapter) {
        this.metricAdapter = metricAdapter;
    }

    public List<MetricDescriptor> resolveForObjective(String objectiveKey, String tenantId) {
        return metricAdapter.metricsForObjective(objectiveKey, tenantId);
    }

    public MetricDescriptor lookupByKey(String key, String tenantId) {
        return resolveForObjective("*", tenantId).stream()
                .filter(m -> m.key().equalsIgnoreCase(key))
                .findFirst()
                .orElseThrow(() -> new RegistryException("Metric not found: " + key));
    }
}
