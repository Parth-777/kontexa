package com.example.BACKEND.catalogue.decision.semantics.catalog;

import com.example.BACKEND.catalogue.decision.contracts.DecisionModels.DimensionDescriptor;
import com.example.BACKEND.catalogue.decision.contracts.DecisionModels.MetricDescriptor;
import com.example.BACKEND.catalogue.decision.contracts.DecisionModels.RegistryResolutionBundle;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Builds a {@link SemanticCatalog} from the tenant registry bundle (approved catalogue schema).
 */
@Component
public class SemanticCatalogBuilder {

    public SemanticCatalog build(RegistryResolutionBundle bundle) {
        if (bundle == null) {
            return new SemanticCatalog("", List.of(), List.of());
        }

        String tableRef = bundle.entities() != null && !bundle.entities().isEmpty()
                ? bundle.entities().getFirst().tableRef()
                : inferTableFromKeys(bundle);

        List<SemanticCatalogEntry> metrics = new ArrayList<>();
        if (bundle.metrics() != null) {
            int rank = 0;
            for (MetricDescriptor m : bundle.metrics()) {
                String col = bareColumn(m.key());
                if (col == null || col.isBlank()) continue;
                String label = humanize(col);
                metrics.add(new SemanticCatalogEntry(
                        m.key(), col, label, "METRIC",
                        m.valueType() != null ? m.valueType() : "NUMERIC",
                        1.0 - (rank * 0.01),
                        MetricAliasGenerator.aliasesFor(col, label)));
                rank++;
            }
        }

        List<SemanticCatalogEntry> dimensions = new ArrayList<>();
        if (bundle.dimensions() != null) {
            int rank = 0;
            for (DimensionDescriptor d : bundle.dimensions()) {
                String col = bareColumn(d.key());
                if (col == null || col.isBlank()) continue;
                dimensions.add(new SemanticCatalogEntry(
                        d.key(), col, humanize(col), "DIMENSION",
                        d.type() != null ? d.type() : "CATEGORICAL",
                        1.0 - (rank * 0.01),
                        DimensionAliasGenerator.aliasesFor(col, humanize(col))));
                rank++;
            }
        }

        metrics.sort(Comparator.comparing(SemanticCatalogEntry::label));
        dimensions.sort(Comparator.comparing(SemanticCatalogEntry::label));
        return new SemanticCatalog(tableRef, List.copyOf(metrics), List.copyOf(dimensions));
    }

    private String inferTableFromKeys(RegistryResolutionBundle bundle) {
        if (bundle.metrics() != null && !bundle.metrics().isEmpty()) {
            return bareTable(bundle.metrics().getFirst().key());
        }
        if (bundle.dimensions() != null && !bundle.dimensions().isEmpty()) {
            return bareTable(bundle.dimensions().getFirst().key());
        }
        return "";
    }

    public static String bareColumn(String registryKey) {
        if (registryKey == null) return "";
        int dot = registryKey.lastIndexOf('.');
        return dot >= 0 ? registryKey.substring(dot + 1) : registryKey;
    }

    static String bareTable(String registryKey) {
        if (registryKey == null) return "";
        int dot = registryKey.indexOf('.');
        return dot >= 0 ? registryKey.substring(0, dot) : registryKey;
    }

    public static String humanize(String column) {
        if (column == null) return "";
        return column.replace('_', ' ').trim();
    }
}
