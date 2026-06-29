package com.example.BACKEND.catalogue.decision.investigation;

import com.example.BACKEND.catalogue.decision.contracts.DecisionModels.QuerySpec;
import com.example.BACKEND.catalogue.semantic.canonical.CanonicalQueryModel;
import com.example.BACKEND.catalogue.semantic.canonical.CanonicalSqlRenderer;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds the investigation warehouse query set by reusing {@link CanonicalSqlRenderer}.
 *
 * <p>Each rendered {@link QuerySpec} is re-keyed with a unique provenance key, because the
 * renderer emits a fixed key for every query. Keys feed both result matching and the
 * Evidence Pack provenance map.
 */
@Component
public class InvestigationQuerySetBuilder {

    public static final String BOUNDS_KEY = "inv__bounds";
    public static final String HEADLINE_BASELINE_KEY = "inv__headline__baseline";
    public static final String HEADLINE_OBSERVATION_KEY = "inv__headline__observation";

    private final CanonicalSqlRenderer renderer;

    public InvestigationQuerySetBuilder(CanonicalSqlRenderer renderer) {
        this.renderer = renderer;
    }

    /**
     * A scalar probe of the latest value of the time column (reuses scalar rendering with
     * a MAX aggregation), scoped by the base filters.
     */
    public QuerySpec boundsSpec(InvestigationSpecBuilder.BaseResolution base) {
        CanonicalQueryModel model = new CanonicalQueryModel(
                new CanonicalQueryModel.MeasureSpec(base.timeColumn(), "MAX"),
                null,
                base.baseFilters(),
                null, null, null, null,
                CanonicalQueryModel.empty().metadata());
        return rekey(BOUNDS_KEY, model, base.qualifiedTableName());
    }

    public QuerySet build(InvestigationSpec spec, int maxMembersPerDimension) {
        List<QuerySpec> specs = new ArrayList<>();
        Map<String, String> meanings = new LinkedHashMap<>();

        // Headline windows (scalar)
        QuerySpec headlineBaseline = rekey(
                HEADLINE_BASELINE_KEY,
                scalarModel(spec, spec.baselineWindow()),
                spec.qualifiedTableName());
        QuerySpec headlineObservation = rekey(
                HEADLINE_OBSERVATION_KEY,
                scalarModel(spec, spec.observationWindow()),
                spec.qualifiedTableName());
        specs.add(headlineBaseline);
        specs.add(headlineObservation);
        meanings.put(HEADLINE_BASELINE_KEY,
                "Headline " + spec.targetMeasure().column() + " in baseline window");
        meanings.put(HEADLINE_OBSERVATION_KEY,
                "Headline " + spec.targetMeasure().column() + " in observation window");

        // Per-dimension breakdowns (grouped), baseline and observation
        Map<String, String> dimensionBaselineKeys = new LinkedHashMap<>();
        Map<String, String> dimensionObservationKeys = new LinkedHashMap<>();
        for (CandidateDimension dim : spec.candidateDimensions()) {
            String baseKey = "inv__dim__" + dim.column() + "__baseline";
            String obsKey = "inv__dim__" + dim.column() + "__observation";
            specs.add(rekey(baseKey,
                    groupedModel(spec, dim, spec.baselineWindow(), maxMembersPerDimension),
                    spec.qualifiedTableName()));
            specs.add(rekey(obsKey,
                    groupedModel(spec, dim, spec.observationWindow(), maxMembersPerDimension),
                    spec.qualifiedTableName()));
            dimensionBaselineKeys.put(dim.column(), baseKey);
            dimensionObservationKeys.put(dim.column(), obsKey);
            meanings.put(baseKey, spec.targetMeasure().column() + " by " + dim.column() + " (baseline)");
            meanings.put(obsKey, spec.targetMeasure().column() + " by " + dim.column() + " (observation)");
        }

        return new QuerySet(
                specs,
                HEADLINE_BASELINE_KEY,
                HEADLINE_OBSERVATION_KEY,
                dimensionBaselineKeys,
                dimensionObservationKeys,
                meanings);
    }

    private CanonicalQueryModel scalarModel(InvestigationSpec spec, TimeWindow window) {
        return new CanonicalQueryModel(
                spec.targetMeasure(),
                null,
                withWindow(spec.baseFilters(), window),
                null, null, null, null,
                CanonicalQueryModel.empty().metadata());
    }

    private CanonicalQueryModel groupedModel(
            InvestigationSpec spec, CandidateDimension dim, TimeWindow window, int limit
    ) {
        return new CanonicalQueryModel(
                spec.targetMeasure(),
                new CanonicalQueryModel.PartitionSpec(dim.column(), null),
                withWindow(spec.baseFilters(), window),
                null, null,
                new CanonicalQueryModel.OrderSpec(spec.targetMeasure().column(), "DESC"),
                limit > 0 ? limit : null,
                CanonicalQueryModel.empty().metadata());
    }

    private static List<CanonicalQueryModel.FilterSpec> withWindow(
            List<CanonicalQueryModel.FilterSpec> base, TimeWindow window
    ) {
        List<CanonicalQueryModel.FilterSpec> filters = new ArrayList<>();
        if (base != null) {
            filters.addAll(base);
        }
        filters.add(new CanonicalQueryModel.FilterSpec(
                window.column(), ">=", window.startInclusive()));
        filters.add(new CanonicalQueryModel.FilterSpec(
                window.column(), "<", window.endExclusive()));
        return filters;
    }

    private QuerySpec rekey(String key, CanonicalQueryModel model, String tableRef) {
        QuerySpec rendered = renderer.render(model, tableRef);
        return new QuerySpec(key, rendered.sql(), rendered.params());
    }

    /**
     * The full investigation query set plus the keys needed to interpret its results.
     */
    public record QuerySet(
            List<QuerySpec> specs,
            String headlineBaselineKey,
            String headlineObservationKey,
            Map<String, String> dimensionBaselineKeys,
            Map<String, String> dimensionObservationKeys,
            Map<String, String> meanings
    ) {}
}
