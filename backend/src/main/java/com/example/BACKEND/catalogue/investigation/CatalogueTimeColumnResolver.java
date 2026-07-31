package com.example.BACKEND.catalogue.investigation;

import com.example.BACKEND.catalogue.decision.contracts.DecisionModels.DimensionDescriptor;
import com.example.BACKEND.catalogue.decision.contracts.DecisionModels.EntityDescriptor;
import com.example.BACKEND.catalogue.decision.contracts.DecisionModels.RegistryResolutionBundle;
import org.springframework.stereotype.Component;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Resolves the warehouse time/partition column from catalogue metadata only.
 * Never reads planner {@code dimensions[0]} or {@link com.example.BACKEND.catalogue.semantic.canonical.CanonicalQueryModel.PartitionSpec#column()}.
 */
@Component
public class CatalogueTimeColumnResolver {

    /**
     * Primary column used for baseline/observation windowing.
     * Priority: timestamp role → TEMPORAL type → date/timestamp data-type flag → entity partition grain.
     */
    public String resolveTimeColumn(RegistryResolutionBundle bundle) {
        if (bundle == null || bundle.dimensions() == null) {
            return null;
        }
        for (DimensionDescriptor d : bundle.dimensions()) {
            if (isTimestampRole(d)) {
                return columnName(d);
            }
        }
        for (DimensionDescriptor d : bundle.dimensions()) {
            if (isTemporalType(d.type())) {
                return columnName(d);
            }
        }
        for (DimensionDescriptor d : bundle.dimensions()) {
            if (isTemporalDimension(d)) {
                return columnName(d);
            }
        }
        String fromGrain = resolvePartitionGrainColumn(bundle);
        if (fromGrain != null) {
            return fromGrain;
        }
        return null;
    }

    /**
     * All catalogue temporal/partition columns — excluded from driver decomposition.
     */
    public List<String> temporalAndPartitionColumns(RegistryResolutionBundle bundle) {
        Set<String> out = new LinkedHashSet<>();
        if (bundle == null) {
            return List.of();
        }
        if (bundle.dimensions() != null) {
            for (DimensionDescriptor d : bundle.dimensions()) {
                if (isTimestampRole(d) || isTemporalType(d.type()) || isTemporalDimension(d)) {
                    String col = columnName(d);
                    if (col != null) {
                        out.add(col);
                    }
                }
            }
        }
        String grain = resolvePartitionGrainColumn(bundle);
        if (grain != null) {
            out.add(grain);
        }
        return List.copyOf(out);
    }

    private String resolvePartitionGrainColumn(RegistryResolutionBundle bundle) {
        if (bundle.entities() == null || bundle.dimensions() == null) {
            return null;
        }
        for (EntityDescriptor entity : bundle.entities()) {
            if (entity.grainKeys() == null) {
                continue;
            }
            for (String grainKey : entity.grainKeys()) {
                String bare = bareColumn(grainKey);
                for (DimensionDescriptor d : bundle.dimensions()) {
                    if (bare.equalsIgnoreCase(columnName(d)) && isTemporalDimension(d)) {
                        return columnName(d);
                    }
                }
            }
        }
        return null;
    }

    static boolean isTemporalDimension(DimensionDescriptor d) {
        if (d == null) {
            return false;
        }
        return isTimestampRole(d) || isTemporalType(d.type());
    }

    static boolean isTimestampRole(DimensionDescriptor d) {
        if (d.type() == null) {
            return false;
        }
        String t = d.type().toUpperCase(Locale.ROOT);
        return t.equals("TIMESTAMP") || t.equals("TIMESTAMP_ROLE");
    }

    static boolean isTemporalType(String type) {
        if (type == null || type.isBlank()) {
            return false;
        }
        String t = type.toUpperCase(Locale.ROOT);
        if (t.equals("TEMPORAL") || t.equals("TIMESTAMP") || t.equals("DATE")
                || t.equals("DATETIME") || t.equals("TIME")) {
            return true;
        }
        String lower = type.toLowerCase(Locale.ROOT);
        return lower.contains("date") || lower.contains("time") || lower.contains("timestamp");
    }

    static String columnName(DimensionDescriptor d) {
        if (d == null) {
            return null;
        }
        String col = d.expression() != null && !d.expression().isBlank() ? d.expression() : d.key();
        return bareColumn(col);
    }

    static String bareColumn(String expression) {
        if (expression == null) {
            return null;
        }
        int dot = expression.lastIndexOf('.');
        return dot >= 0 ? expression.substring(dot + 1) : expression;
    }
}
