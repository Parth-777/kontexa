package com.example.BACKEND.catalogue.decision.metricpack;

import com.example.BACKEND.catalogue.decision.contracts.DecisionModels.*;

import java.util.List;

/**
 * Extension point for analytical frameworks.
 *
 * Each MetricPack knows how to translate a {@link RegistryResolutionBundle}
 * into concrete {@link QuerySpec}s for the warehouse executor.
 *
 * Implementations are Spring beans collected by {@link MetricPackPlanner}.
 */
public interface MetricPack {

    /** Unique identifier for this pack (e.g. "RANKING_PACK"). */
    String packKey();

    /** Returns true when this pack handles the given objective. */
    boolean supports(String objectiveKey);

    /** Generate warehouse-native query specs from the resolved bundle. */
    List<QuerySpec> buildQuerySpecs(RegistryResolutionBundle bundle, IntentResolution intent);
}
