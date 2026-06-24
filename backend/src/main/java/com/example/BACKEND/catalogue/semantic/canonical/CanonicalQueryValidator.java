package com.example.BACKEND.catalogue.semantic.canonical;

import com.example.BACKEND.catalogue.semantic.phase2.ApprovedCatalogueSnapshot;
import com.example.BACKEND.catalogue.semantic.phase2.SemanticPlanningProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Structural validation for {@link CanonicalQueryModel} prior to canonical SQL rendering.
 * No inference, defaults, or semantic repair.
 */
@Component
public class CanonicalQueryValidator {

    private static final Set<String> ALLOWED_AGGREGATIONS =
            Set.of("SUM", "AVG", "COUNT", "MIN", "MAX");
    private static final Set<String> ALLOWED_FILTER_OPS =
            Set.of("=", "!=", "<>", ">", ">=", "<", "<=", "IN", "LIKE");
    private static final Set<String> ALLOWED_TIME_GRAINS =
            Set.of("DAY", "WEEK", "MONTH", "QUARTER", "YEAR");
    private static final Set<String> ALLOWED_BIVARIATE_FUNCTIONS = Set.of("CORR");
    private static final Set<String> ALLOWED_INTENTS = Set.of(
            "RANKING", "CONTRIBUTION", "TREND", "COMPARISON", "DISTRIBUTION", "RELATIONSHIP", "SCALAR",
            "GROWTH", "PARETO", "OUTLIER", "VARIANCE");

    private final SemanticPlanningProperties properties;

    public CanonicalQueryValidator(SemanticPlanningProperties properties) {
        this.properties = properties;
    }

    public CanonicalQueryValidationResult validate(
            CanonicalQueryModel model,
            ApprovedCatalogueSnapshot catalogue
    ) {
        List<String> issues = new ArrayList<>();
        if (model == null) {
            return CanonicalQueryValidationResult.fail("null canonical model");
        }
        if (catalogue == null || catalogue.qualifiedTableName() == null
                || catalogue.qualifiedTableName().isBlank()) {
            issues.add("missing table reference");
        }
        if (model.metadata() != null && model.metadata().confidence() < properties.getMinConfidence()) {
            issues.add("confidence below threshold");
        }

        validateMetadata(model, issues);

        boolean bivariateQuery = model.bivariate() != null
                && model.bivariate().function() != null
                && !model.bivariate().function().isBlank();

        if (bivariateQuery) {
            validateBivariate(model, catalogue, issues);
        } else {
            validateMeasure(model, catalogue, issues);
            validatePartition(model, catalogue, issues);
            validateRatio(model, catalogue, issues);
        }

        validateFilters(model, catalogue, issues);
        validateLimit(model, issues);
        validateTimeGrain(model, issues);

        return issues.isEmpty()
                ? CanonicalQueryValidationResult.ok()
                : CanonicalQueryValidationResult.fail(issues);
    }

    private static void validateMetadata(CanonicalQueryModel model, List<String> issues) {
        if (model.metadata() == null || model.metadata().intent() == null
                || model.metadata().intent().isBlank()) {
            issues.add("metadata.intent required");
            return;
        }
        String intent = model.metadata().intent().toUpperCase(Locale.ROOT);
        if (!ALLOWED_INTENTS.contains(intent)) {
            issues.add("unsupported metadata.intent: " + model.metadata().intent());
        }
    }

    private static void validateMeasure(
            CanonicalQueryModel model,
            ApprovedCatalogueSnapshot catalogue,
            List<String> issues
    ) {
        if (model.measure() == null || model.measure().column() == null
                || model.measure().column().isBlank()) {
            issues.add("measure.column required");
            return;
        }
        requireColumn(catalogue, model.measure().column(), "measure", issues);
        String agg = model.measure().aggregation();
        if (agg == null || agg.isBlank()) {
            issues.add("measure.aggregation required");
        } else if (!ALLOWED_AGGREGATIONS.contains(agg.toUpperCase(Locale.ROOT))) {
            issues.add("unsupported measure.aggregation: " + agg);
        }
    }

    private static void validatePartition(
            CanonicalQueryModel model,
            ApprovedCatalogueSnapshot catalogue,
            List<String> issues
    ) {
        if (model.partition() == null) {
            return;
        }
        String column = model.partition().column();
        if (column == null || column.isBlank()) {
            if (model.partition().timeGrain() != null && !model.partition().timeGrain().isBlank()) {
                issues.add("partition.column required when timeGrain is set");
            }
            return;
        }
        requireColumn(catalogue, column, "partition", issues);
    }

    private static void validateRatio(
            CanonicalQueryModel model,
            ApprovedCatalogueSnapshot catalogue,
            List<String> issues
    ) {
        if (model.ratio() == null || model.ratio().denominator() == null) {
            return;
        }
        CanonicalQueryModel.MeasureSpec denom = model.ratio().denominator();
        if (denom.column() != null && !denom.column().isBlank()) {
            requireColumn(catalogue, denom.column(), "ratio.denominator", issues);
        }
        if (denom.aggregation() != null && !denom.aggregation().isBlank()
                && !ALLOWED_AGGREGATIONS.contains(denom.aggregation().toUpperCase(Locale.ROOT))) {
            issues.add("unsupported ratio.denominator.aggregation: " + denom.aggregation());
        }
    }

    private static void validateBivariate(
            CanonicalQueryModel model,
            ApprovedCatalogueSnapshot catalogue,
            List<String> issues
    ) {
        CanonicalQueryModel.BivariateSpec b = model.bivariate();
        if (b.columnA() == null || b.columnA().isBlank()) {
            issues.add("bivariate.columnA required");
        } else {
            requireColumn(catalogue, b.columnA(), "bivariate.columnA", issues);
        }
        if (b.columnB() == null || b.columnB().isBlank()) {
            issues.add("bivariate.columnB required");
        } else {
            requireColumn(catalogue, b.columnB(), "bivariate.columnB", issues);
        }
        if (b.function() == null || !ALLOWED_BIVARIATE_FUNCTIONS.contains(b.function().toUpperCase(Locale.ROOT))) {
            issues.add("unsupported bivariate.function: " + b.function());
        }
        if (b.columnA() != null && b.columnB() != null
                && b.columnA().equalsIgnoreCase(b.columnB())) {
            issues.add("bivariate operands must be distinct");
        }
    }

    private static void validateFilters(
            CanonicalQueryModel model,
            ApprovedCatalogueSnapshot catalogue,
            List<String> issues
    ) {
        if (model.filters() == null) {
            return;
        }
        for (CanonicalQueryModel.FilterSpec filter : model.filters()) {
            if (filter.column() == null || filter.column().isBlank()) {
                issues.add("filter.column required");
                continue;
            }
            requireColumn(catalogue, filter.column(), "filter", issues);
            String op = filter.operator() != null ? filter.operator().toUpperCase(Locale.ROOT) : "";
            if (!ALLOWED_FILTER_OPS.contains(op)) {
                issues.add("unsupported filter operator: " + filter.operator());
            }
            if (filter.value() == null || filter.value().isBlank()) {
                issues.add("filter.value required for column " + filter.column());
            }
        }
    }

    private static void validateLimit(CanonicalQueryModel model, List<String> issues) {
        if (model.limit() != null && model.limit() <= 0) {
            issues.add("limit must be positive when set");
        }
    }

    private static void validateTimeGrain(CanonicalQueryModel model, List<String> issues) {
        if (model.partition() == null || model.partition().timeGrain() == null
                || model.partition().timeGrain().isBlank()) {
            return;
        }
        String grain = model.partition().timeGrain().toUpperCase(Locale.ROOT);
        if (!ALLOWED_TIME_GRAINS.contains(grain)) {
            issues.add("unsupported partition.timeGrain: " + model.partition().timeGrain());
        }
    }

    private static void requireColumn(
            ApprovedCatalogueSnapshot catalogue,
            String column,
            String field,
            List<String> issues
    ) {
        if (catalogue != null && !catalogue.hasColumn(column)) {
            issues.add(field + " column not in catalogue: " + column);
        }
    }
}
