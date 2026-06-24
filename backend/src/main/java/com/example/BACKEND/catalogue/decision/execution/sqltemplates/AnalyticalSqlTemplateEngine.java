package com.example.BACKEND.catalogue.decision.execution.sqltemplates;

import com.example.BACKEND.catalogue.decision.contracts.DecisionModels.QuerySpec;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.Map;

/**
 * Routes analytical intent to deterministic SQL templates.
 */
@Component
public class AnalyticalSqlTemplateEngine {

    private final IntentAggregationStrategy aggregationStrategy;
    private final ContributionSqlTemplate contribution;
    private final TrendSqlTemplate trend;
    private final RankingSqlTemplate ranking;
    private final ComparisonSqlTemplate comparison;
    private final DistributionSqlTemplate distribution;
    private final EfficiencySqlTemplate efficiency;
    private final RelationshipSqlTemplate relationship;

    public AnalyticalSqlTemplateEngine(
            IntentAggregationStrategy aggregationStrategy,
            ContributionSqlTemplate contribution,
            TrendSqlTemplate trend,
            RankingSqlTemplate ranking,
            ComparisonSqlTemplate comparison,
            DistributionSqlTemplate distribution,
            EfficiencySqlTemplate efficiency,
            RelationshipSqlTemplate relationship
    ) {
        this.aggregationStrategy = aggregationStrategy;
        this.contribution = contribution;
        this.trend = trend;
        this.ranking = ranking;
        this.comparison = comparison;
        this.distribution = distribution;
        this.efficiency = efficiency;
        this.relationship = relationship;
    }

    public QuerySpec generate(TemplateContext ctx) {
        String sql = switch (ctx.intent()) {
            case CONTRIBUTION -> contribution.render(ctx);
            case TREND -> trend.render(ctx);
            case RANKING -> ranking.render(ctx);
            case COMPARISON -> comparison.render(ctx);
            case DISTRIBUTION -> distribution.render(ctx);
            case EFFICIENCY -> efficiency.render(ctx);
            case RELATIONSHIP -> relationship.render(ctx);
        };
        String key = "tpl__" + ctx.intent().name().toLowerCase(Locale.ROOT)
                + "__" + (ctx.candidateId() != null ? ctx.candidateId() : "primary");
        return new QuerySpec(key, sql, Map.of(
                "metric", ctx.revenueMetric(),
                "dimension", ctx.dimensionColumn() != null ? ctx.dimensionColumn() : "",
                "relationship_variable", ctx.relationshipVariable() != null ? ctx.relationshipVariable() : "",
                "intent", ctx.intent().name(),
                "aggregation", aggregationStrategy.forSqlIntent(ctx.intent(), ctx.revenueMetric()).aggregation().name(),
                "table", ctx.tableRef()
        ));
    }

    public static AnalyticalIntentKind detectIntent(String question) {
        String q = question != null ? question.toLowerCase(Locale.ROOT) : "";
        if (q.contains("top ") || q.contains("rank") || q.contains("highest") || q.contains("lowest")) {
            return AnalyticalIntentKind.RANKING;
        }
        if (q.contains(" vs ") || q.contains("weekend") || q.contains("compare") || q.contains("weekday")) {
            return AnalyticalIntentKind.COMPARISON;
        }
        if (q.contains("trend") || q.contains("over time") || q.contains("by hour")
                || q.contains("hourly") || q.contains("monthly")) {
            return AnalyticalIntentKind.TREND;
        }
        if (q.contains("distribution") || q.contains("histogram") || q.contains("average fare")) {
            return AnalyticalIntentKind.DISTRIBUTION;
        }
        if (q.contains("contribute") || q.contains("affect") || q.contains("impact")
                || q.contains("drive") || q.contains("share") || q.contains("revenue by")) {
            return AnalyticalIntentKind.CONTRIBUTION;
        }
        return AnalyticalIntentKind.CONTRIBUTION;
    }

    public static String detectDimension(String question, DatasetProfileRegistry.DatasetProfile profile) {
        String q = question != null ? question.toLowerCase(Locale.ROOT) : "";
        if (q.contains("distance") || q.contains("mile")) return profile.primaryDistanceDimension();
        if (q.contains("zone") || q.contains("pickup")) return profile.pickupZoneDimension();
        if (q.contains("hour") || q.contains("time")) return profile.primaryTimeDimension();
        if (q.contains("weekend") || q.contains("weekday")) return "weekend_flag";
        if (q.contains("tip")) return HardMetricMappings.TIP_METRIC;
        return null;
    }
}
