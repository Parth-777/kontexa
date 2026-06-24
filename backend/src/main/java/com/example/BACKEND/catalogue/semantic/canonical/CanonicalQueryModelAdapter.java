package com.example.BACKEND.catalogue.semantic.canonical;

import com.example.BACKEND.catalogue.semantic.phase2.StructuredSemanticPlan;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Pure field translation from {@link StructuredSemanticPlan} to {@link CanonicalQueryModel}.
 * No business logic, inference, defaults, intent routing, or aggregation rewrites.
 */
@Component
public class CanonicalQueryModelAdapter {

    public CanonicalQueryModel adapt(StructuredSemanticPlan plan) {
        if (plan == null) {
            return CanonicalQueryModel.empty();
        }

        String primaryAgg = plan.aggregations() != null ? plan.aggregations().primary() : null;
        String secondaryAgg = plan.aggregations() != null ? plan.aggregations().secondary() : null;
        List<String> dimensions = plan.dimensions() != null ? List.copyOf(plan.dimensions()) : List.of();

        CanonicalQueryModel.MeasureSpec measure = plan.metric() != null
                ? new CanonicalQueryModel.MeasureSpec(plan.metric(), primaryAgg)
                : null;

        CanonicalQueryModel.PartitionSpec partition = null;
        if (!dimensions.isEmpty() || plan.timeGrain() != null) {
            partition = new CanonicalQueryModel.PartitionSpec(
                    dimensions.isEmpty() ? null : dimensions.get(0),
                    plan.timeGrain());
        }

        List<CanonicalQueryModel.FilterSpec> filters = plan.filters() == null
                ? List.of()
                : plan.filters().stream()
                        .map(f -> new CanonicalQueryModel.FilterSpec(f.column(), f.operator(), f.value()))
                        .toList();

        CanonicalQueryModel.RatioSpec ratio = null;
        if (plan.secondaryMetric() != null || secondaryAgg != null) {
            ratio = new CanonicalQueryModel.RatioSpec(
                    plan.intent(),
                    new CanonicalQueryModel.MeasureSpec(plan.secondaryMetric(), secondaryAgg));
        }

        CanonicalQueryModel.BivariateSpec bivariate = null;
        if (plan.relationshipVariable() != null) {
            bivariate = new CanonicalQueryModel.BivariateSpec(
                    plan.metric(),
                    plan.relationshipVariable(),
                    "RELATIONSHIP".equalsIgnoreCase(plan.intent()) ? "CORR" : null);
        }

        CanonicalQueryModel.OrderSpec ordering = plan.ordering() != null
                ? new CanonicalQueryModel.OrderSpec(plan.ordering().column(), plan.ordering().direction())
                : null;

        CanonicalQueryModel.PlannerMetadata metadata = new CanonicalQueryModel.PlannerMetadata(
                plan.intent(),
                plan.confidence(),
                plan.reasoning(),
                dimensions,
                plan.secondaryMetric(),
                plan.relationshipVariable());

        return new CanonicalQueryModel(
                measure, partition, filters, ratio, bivariate, ordering, plan.limit(), metadata);
    }
}
