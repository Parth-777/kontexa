package com.example.BACKEND.catalogue.decision.metricpack;

import com.example.BACKEND.catalogue.decision.contracts.DecisionModels.*;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Turns an {@link IntentResolution} + {@link RegistryResolutionBundle} into a
 * {@link MetricPackExecutionPlan} — a list of warehouse-native {@link QuerySpec}s.
 *
 * Delegates to objective-specific {@link MetricPack} implementations.
 * Adding a new analytical capability = adding a new MetricPack bean.
 */
@Service
public class MetricPackPlanner {

    private final List<MetricPack> packs;

    public MetricPackPlanner(List<MetricPack> packs) {
        this.packs = packs;
    }

    public MetricPackExecutionPlan plan(IntentResolution intent, RegistryResolutionBundle bundle) {
        MetricPack pack = selectPack(intent.objectiveKey());
        List<QuerySpec> queries = pack.buildQuerySpecs(bundle, intent);
        return new MetricPackExecutionPlan(pack.packKey(), "1.0", intent.tenantId(), queries);
    }

    private MetricPack selectPack(String objectiveKey) {
        return packs.stream()
                .filter(p -> p.supports(objectiveKey))
                .findFirst()
                .orElseGet(GeneralAnalysisPack::new);
    }
}
