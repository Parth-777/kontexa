package com.example.BACKEND.catalogue.decision.registry;

import com.example.BACKEND.catalogue.decision.contracts.DecisionModels.*;
import com.example.BACKEND.catalogue.decision.registry.adapter.SchemaDiscoveryAdapter;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Entity Registry.
 *
 * Derives entity descriptors from the live schema catalogue via the
 * {@link SchemaDiscoveryAdapter}.  Each table in the tenant schema
 * becomes an entity with its primary-key columns as grain keys and
 * its catalogue tags as semantic tags.
 */
@Component
public class EntityRegistry {

    private final SchemaDiscoveryAdapter schemaAdapter;

    public EntityRegistry(SchemaDiscoveryAdapter schemaAdapter) {
        this.schemaAdapter = schemaAdapter;
    }

    public List<EntityDescriptor> resolveForTenant(String tenantId) {
        return schemaAdapter.discoverEntities(tenantId);
    }

    public EntityDescriptor lookupByKey(String key, String tenantId) {
        return resolveForTenant(tenantId).stream()
                .filter(e -> e.key().equalsIgnoreCase(key))
                .findFirst()
                .orElseThrow(() -> new RegistryException("Entity not found: " + key));
    }
}
