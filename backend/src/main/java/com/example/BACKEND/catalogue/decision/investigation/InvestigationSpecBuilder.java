package com.example.BACKEND.catalogue.decision.investigation;

import com.example.BACKEND.catalogue.semantic.canonical.CanonicalQueryModel;
import com.example.BACKEND.catalogue.semantic.phase2.ApprovedCatalogueSnapshot;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Builds an {@link InvestigationSpec} from the canonical plan and approved catalogue.
 *
 * <p>Resolution is split so the runtime can probe the latest data date between base
 * resolution and window derivation:
 * <ol>
 *   <li>{@link #resolveBase} determines applicability, measure, time column, base filters,
 *       and candidate dimensions.</li>
 *   <li>{@link #finalize} composes the final spec once windows are known.</li>
 * </ol>
 */
@Component
public class InvestigationSpecBuilder {

    /** V1 supports additive decomposition only. */
    private static final Set<String> ADDITIVE_AGGREGATIONS = Set.of("SUM", "COUNT");

    private final CandidateDimensionEnumerator dimensionEnumerator;

    public InvestigationSpecBuilder(CandidateDimensionEnumerator dimensionEnumerator) {
        this.dimensionEnumerator = dimensionEnumerator;
    }

    public BaseResolution resolveBase(
            String question,
            CanonicalQueryModel model,
            String requestedDirection,
            ApprovedCatalogueSnapshot catalogue,
            InvestigationProperties properties
    ) {
        if (model == null || model.measure() == null
                || model.measure().column() == null || model.measure().column().isBlank()) {
            return BaseResolution.notApplicable(question, "no target measure resolved");
        }
        String aggregation = model.measure().aggregation() != null
                ? model.measure().aggregation().toUpperCase(Locale.ROOT) : "SUM";
        if (!ADDITIVE_AGGREGATIONS.contains(aggregation)) {
            return BaseResolution.notApplicable(question,
                    "V1 supports additive (SUM/COUNT) measures only; got " + aggregation);
        }

        String measureColumn = model.measure().column();
        String timeColumn = resolveTimeColumn(catalogue);
        if (timeColumn == null) {
            return BaseResolution.notApplicable(question,
                    "no time column available for change windowing");
        }

        String grain = model.partition() != null && model.partition().timeGrain() != null
                && !model.partition().timeGrain().isBlank()
                ? model.partition().timeGrain()
                : properties.getWindow().getDefaultGrain();

        List<CandidateDimension> candidates = dimensionEnumerator.enumerate(
                catalogue, timeColumn, measureColumn, properties.getMaxCandidateDimensions());
        if (candidates.isEmpty()) {
            return BaseResolution.notApplicable(question,
                    "no eligible catalogue dimensions for decomposition");
        }

        return new BaseResolution(
                question,
                catalogue.qualifiedTableName(),
                new CanonicalQueryModel.MeasureSpec(measureColumn, aggregation),
                timeColumn,
                grain,
                requestedDirection,
                model.filters() != null ? model.filters() : List.of(),
                candidates,
                true,
                null);
    }

    public InvestigationSpec finalize(BaseResolution base, ChangeWindowPolicy.Windows windows) {
        return new InvestigationSpec(
                base.question(),
                base.qualifiedTableName(),
                base.targetMeasure(),
                base.timeColumn(),
                windows.grain(),
                windows.baseline(),
                windows.observation(),
                base.requestedDirection(),
                base.baseFilters(),
                base.candidateDimensions(),
                true,
                null);
    }

    private static String resolveTimeColumn(ApprovedCatalogueSnapshot catalogue) {
        if (catalogue == null) {
            return null;
        }
        for (ApprovedCatalogueSnapshot.CatalogueColumn col : catalogue.columns()) {
            String role = col.role() != null ? col.role().toLowerCase(Locale.ROOT) : "";
            if (role.contains("timestamp")) {
                return col.columnName();
            }
        }
        for (ApprovedCatalogueSnapshot.CatalogueColumn col : catalogue.columns()) {
            String type = col.dataType() != null ? col.dataType().toUpperCase(Locale.ROOT) : "";
            if (type.contains("DATE") || type.contains("TIMESTAMP") || type.contains("DATETIME")) {
                return col.columnName();
            }
        }
        return null;
    }

    /**
     * Base resolution prior to window derivation.
     */
    public record BaseResolution(
            String question,
            String qualifiedTableName,
            CanonicalQueryModel.MeasureSpec targetMeasure,
            String timeColumn,
            String grain,
            String requestedDirection,
            List<CanonicalQueryModel.FilterSpec> baseFilters,
            List<CandidateDimension> candidateDimensions,
            boolean applicable,
            String inapplicableReason
    ) {
        static BaseResolution notApplicable(String question, String reason) {
            return new BaseResolution(question, null, null, null, null, null,
                    List.of(), List.of(), false, reason);
        }
    }
}
