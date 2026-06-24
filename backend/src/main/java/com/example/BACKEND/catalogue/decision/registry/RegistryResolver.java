package com.example.BACKEND.catalogue.decision.registry;

import com.example.BACKEND.catalogue.decision.contracts.DecisionModels.*;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Top-level registry resolution service.
 *
 * Given an {@link IntentResolution}, it hydrates the full
 * {@link RegistryResolutionBundle} by delegating to the three
 * sub-registries and the schema-discovery adapter.
 */
@Service
public class RegistryResolver {

    private final EntityRegistry    entityRegistry;
    private final MetricRegistry    metricRegistry;
    private final DimensionRegistry dimensionRegistry;
    private final ObjectiveRegistry objectiveRegistry;

    public RegistryResolver(
            EntityRegistry    entityRegistry,
            MetricRegistry    metricRegistry,
            DimensionRegistry dimensionRegistry,
            ObjectiveRegistry objectiveRegistry
    ) {
        this.entityRegistry    = entityRegistry;
        this.metricRegistry    = metricRegistry;
        this.dimensionRegistry = dimensionRegistry;
        this.objectiveRegistry = objectiveRegistry;
    }

    public RegistryResolutionBundle resolve(IntentResolution intent) {
        ObjectiveDescriptor objective = objectiveRegistry.lookup(intent.objectiveKey());

        List<EntityDescriptor>    entities   = entityRegistry.resolveForTenant(intent.tenantId());
        List<MetricDescriptor>    metrics    = metricRegistry.resolveForObjective(
                                                   intent.objectiveKey(), intent.tenantId());
        List<DimensionDescriptor> dimensions = dimensionRegistry.resolveForObjective(
                                                   intent.objectiveKey(), intent.tenantId());

        return new RegistryResolutionBundle(entities, metrics, dimensions, objective);
    }
}
