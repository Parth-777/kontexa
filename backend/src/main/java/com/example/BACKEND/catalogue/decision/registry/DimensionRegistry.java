package com.example.BACKEND.catalogue.decision.registry;

import com.example.BACKEND.catalogue.decision.contracts.DecisionModels.*;
import com.example.BACKEND.catalogue.decision.registry.adapter.SchemaDiscoveryAdapter;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Dimension Registry.
 *
 * Surfaces categorical/temporal columns as analytical dimensions.
 * Derived from schema metadata; no domain-specific logic.
 */
@Component
public class DimensionRegistry {

    private final SchemaDiscoveryAdapter schemaAdapter;

    public DimensionRegistry(SchemaDiscoveryAdapter schemaAdapter) {
        this.schemaAdapter = schemaAdapter;
    }

    public List<DimensionDescriptor> resolveForObjective(String objectiveKey, String tenantId) {
        return schemaAdapter.discoverDimensions(tenantId, objectiveKey);
    }
}
