package com.example.BACKEND.catalogue.investigation;

import com.example.BACKEND.catalogue.decision.contracts.DecisionModels.DimensionDescriptor;
import com.example.BACKEND.catalogue.decision.contracts.DecisionModels.RegistryResolutionBundle;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Selects catalogue dimensions eligible for contrast decomposition.
 */
@Component
public class CandidateDimensionEnumerator {

    private final InvestigationProperties properties;
    private final CatalogueTimeColumnResolver timeColumnResolver;

    public CandidateDimensionEnumerator(InvestigationProperties properties,
                                        CatalogueTimeColumnResolver timeColumnResolver) {
        this.properties = properties;
        this.timeColumnResolver = timeColumnResolver;
    }

    public List<CandidateDimension> enumerate(RegistryResolutionBundle bundle,
                                              InvestigationObjective objective,
                                              Set<String> contrastColumns) {
        if (bundle == null || bundle.dimensions() == null || objective == null) {
            return List.of();
        }

        Set<String> excluded = new LinkedHashSet<>();
        if (objective.measure() != null && objective.measure().column() != null) {
            excluded.add(objective.measure().column().toLowerCase(Locale.ROOT));
        }
        if (objective.measure() != null && objective.measure().grain() != null) {
            for (String g : objective.measure().grain()) {
                excluded.add(g.toLowerCase(Locale.ROOT));
            }
        }
        if (contrastColumns != null) {
            for (String c : contrastColumns) {
                excluded.add(c.toLowerCase(Locale.ROOT));
            }
        }
        if (objective.decomposition() != null && objective.decomposition().exclude() != null) {
            for (String c : objective.decomposition().exclude()) {
                excluded.add(c.toLowerCase(Locale.ROOT));
            }
        }
        for (String temporal : timeColumnResolver.temporalAndPartitionColumns(bundle)) {
            excluded.add(temporal.toLowerCase(Locale.ROOT));
        }

        Set<String> hinted = new LinkedHashSet<>();
        if (objective.decomposition() != null && objective.decomposition().dimensions() != null) {
            for (String d : objective.decomposition().dimensions()) {
                if (d != null && !excluded.contains(d.toLowerCase(Locale.ROOT))) {
                    hinted.add(d.toLowerCase(Locale.ROOT));
                }
            }
        }

        List<CandidateDimension> out = new ArrayList<>();
        if (!hinted.isEmpty()) {
            for (DimensionDescriptor d : bundle.dimensions()) {
                String column = CatalogueTimeColumnResolver.columnName(d);
                if (column == null || !hinted.contains(column.toLowerCase(Locale.ROOT))) {
                    continue;
                }
                out.add(toCandidate(d, column));
                if (out.size() >= properties.getMaxCandidateDimensions()) {
                    return out;
                }
            }
        }

        for (DimensionDescriptor d : bundle.dimensions()) {
            String column = CatalogueTimeColumnResolver.columnName(d);
            if (column == null || column.isBlank()) {
                continue;
            }
            if (excluded.contains(column.toLowerCase(Locale.ROOT))) {
                continue;
            }
            if (CatalogueTimeColumnResolver.isTemporalDimension(d)) {
                continue;
            }
            if (hinted.contains(column.toLowerCase(Locale.ROOT))) {
                continue;
            }
            out.add(toCandidate(d, column));
            if (out.size() >= properties.getMaxCandidateDimensions()) {
                break;
            }
        }
        return out;
    }

    private CandidateDimension toCandidate(DimensionDescriptor d, String column) {
        return new CandidateDimension(
                column,
                d.key() != null ? CatalogueTimeColumnResolver.bareColumn(d.key()) : column,
                "catalogue dimension (type=" + d.type() + ")");
    }
}
