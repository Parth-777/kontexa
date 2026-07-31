package com.example.BACKEND.catalogue.semantic.canonical;

import com.example.BACKEND.catalogue.decision.contracts.DecisionModels.QuerySpec;
import com.example.BACKEND.catalogue.semantic.canonical.CanonicalQueryModel.MeasureSpec;
import com.example.BACKEND.catalogue.semantic.canonical.CanonicalQueryModel.PartitionSpec;
import com.example.BACKEND.catalogue.semantic.canonical.CanonicalQueryModel.PlannerMetadata;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

class CanonicalSqlRendererNestedTest {

    private final CanonicalSqlRenderer renderer = new CanonicalSqlRenderer();

    @Test
    void rendersGroupedQueryWithNestedPartition() {
        CanonicalQueryModel model = new CanonicalQueryModel(
                new MeasureSpec("visitId", "COUNT"),
                new PartitionSpec("trafficSource.source", null),
                List.of(),
                null,
                null,
                null,
                10,
                new PlannerMetadata("RANKING", 0.9, null, List.of("trafficSource.source"), null, null));

        QuerySpec spec = renderer.render(model, "ga_sessions_20160801");
        assertTrue(spec.sql().contains("trafficSource.source"));
        assertTrue(spec.sql().contains("GROUP BY trafficSource.source"));
    }

    @Test
    void ordersGroupedNestedMeasureBySelectAliasNotBareLeaf() {
        CanonicalQueryModel model = new CanonicalQueryModel(
                new MeasureSpec("totals.visits", "SUM"),
                new PartitionSpec("trafficSource.source", null),
                List.of(),
                null,
                null,
                new CanonicalQueryModel.OrderSpec("totals.visits", "DESC"),
                1,
                new PlannerMetadata("RANKING", 1.0, null, List.of("trafficSource.source"), null, null));

        String sql = renderer.render(model, "google_analytics.ga_sessions_20160801").sql();
        assertTrue(sql.contains("SUM(totals.visits) AS visits"));
        assertTrue(sql.contains("GROUP BY trafficSource.source"));
        assertTrue(sql.contains("ORDER BY visits DESC"));
        assertTrue(!sql.contains("ORDER BY totals.visits"),
                "ORDER BY must not reference the bare measure leaf under GROUP BY: " + sql);
        assertTrue(sql.contains("LIMIT 1"));
    }

    @Test
    void rendersFlatNycTaxiQueryUnchanged() {
        CanonicalQueryModel model = new CanonicalQueryModel(
                new MeasureSpec("tip_amount", "AVG"),
                new PartitionSpec("payment_type", null),
                List.of(),
                null,
                null,
                null,
                10,
                new PlannerMetadata("RANKING", 0.9, null, List.of("payment_type"), null, null));

        QuerySpec spec = renderer.render(model, "yellow_taxi_trips");
        assertTrue(spec.sql().contains("payment_type"));
        assertTrue(spec.sql().contains("GROUP BY payment_type"));
    }
}
