package com.example.BACKEND.catalogue.semantic.canonical;

import com.example.BACKEND.catalogue.decision.semantics.catalog.MetricResolutionTestSupport;
import com.example.BACKEND.catalogue.decision.transforms.BigQueryDialect;
import com.example.BACKEND.catalogue.decision.transforms.SnowflakeDialect;
import com.example.BACKEND.catalogue.schema.SqlFieldReferenceEmitter;
import com.example.BACKEND.catalogue.semantic.phase2.SemanticCatalogueFactory;
import com.example.BACKEND.catalogue.semantic.phase2.StructuredSemanticPlan;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CanonicalQueryWarehouseNeutralTest {

    @Test
    void sameCanonicalModelRendersOnceThenDialectsOnlyChangeSqlFragments() {
        var plan = new StructuredSemanticPlan(
                "COMPARISON", "profit_margin", null,
                List.of("oil_field"), List.of(),
                new StructuredSemanticPlan.SemanticAggregations("AVG", null),
                new StructuredSemanticPlan.SemanticOrdering("profit_margin", "DESC"),
                5, null, null,
                0.9, "test", List.of());

        CanonicalQueryModel canonical = new CanonicalQueryModelAdapter().adapt(plan);
        var catalogue = SemanticCatalogueFactory.catalogueFrom(null, MetricResolutionTestSupport.oilBundle());
        CanonicalSqlRenderer renderer = new CanonicalSqlRenderer();

        String sql = renderer.render(canonical, catalogue.qualifiedTableName()).sql();
        String sqlAgain = renderer.render(canonical, catalogue.qualifiedTableName()).sql();

        assertEquals(sql, sqlAgain);
        assertFalse(sql.contains("snowflake"));
        assertFalse(sql.contains("bigquery"));
        assertTrue(sql.toUpperCase().contains("AVG(PROFIT_MARGIN)"));

        BigQueryDialect bq = new BigQueryDialect();
        SnowflakeDialect sf = new SnowflakeDialect();
        assertEquals("`profit_margin`", SqlFieldReferenceEmitter.emitQuotedReference("profit_margin", bq));
        assertEquals("\"profit_margin\"", SqlFieldReferenceEmitter.emitQuotedReference("profit_margin", sf));
    }
}
