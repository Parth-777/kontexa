package com.example.BACKEND.catalogue.decision.metricpack;

import com.example.BACKEND.catalogue.decision.contracts.DecisionModels.*;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Fallback MetricPack for GENERAL_ANALYSIS and unrecognised objective keys.
 *
 * Generates a lightweight summary query for every entity: row count,
 * and aggregate totals for up to 3 numeric metrics.
 */
@Component
public class GeneralAnalysisPack implements MetricPack {

    private static final int MAX_METRICS_PER_ENTITY = 3;

    @Override
    public String packKey() { return "GENERAL_PACK"; }

    @Override
    public boolean supports(String objectiveKey) {
        return "GENERAL_ANALYSIS".equals(objectiveKey);
    }

    @Override
    public List<QuerySpec> buildQuerySpecs(RegistryResolutionBundle bundle, IntentResolution intent) {
        List<QuerySpec> specs = new ArrayList<>();

        for (EntityDescriptor entity : bundle.entities()) {
            // Always include a row count
            specs.add(new QuerySpec(
                    "rowcount__" + entity.key(),
                    "SELECT COUNT(*) AS row_count FROM " + entity.tableRef(),
                    Map.of()
            ));

            // Add aggregate queries for the first N metrics
            List<MetricDescriptor> entityMetrics = bundle.metrics().stream()
                    .filter(m -> m.key().startsWith(entity.tableRef() + "."))
                    .limit(MAX_METRICS_PER_ENTITY)
                    .toList();

            for (MetricDescriptor metric : entityMetrics) {
                String colOnly = metric.key().split("\\.")[1];
                String sql = String.format(
                        "SELECT SUM(%s) AS total, AVG(%s) AS average, MAX(%s) AS max_val, MIN(%s) AS min_val FROM %s",
                        colOnly, colOnly, colOnly, colOnly, entity.tableRef()
                );
                specs.add(new QuerySpec("summary__" + entity.key() + "__" + colOnly, sql, Map.of()));
            }
        }

        return specs;
    }
}
