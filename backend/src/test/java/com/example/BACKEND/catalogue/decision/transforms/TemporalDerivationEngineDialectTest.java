package com.example.BACKEND.catalogue.decision.transforms;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TemporalDerivationEngineDialectTest {

    @Test
    void weekendDerivationUsesInjectedWarehouseDialect() {
        TemporalDerivationEngine bqEngine = new TemporalDerivationEngine(new BigQueryDialect());
        TemporalDerivationEngine sfEngine = new TemporalDerivationEngine(new SnowflakeDialect());

        DerivedDimensionSpec bq = bqEngine.derive(SemanticConcept.WEEKEND_DAY, "pickup_datetime", "weekend");
        DerivedDimensionSpec sf = sfEngine.derive(SemanticConcept.WEEKEND_DAY, "pickup_datetime", "weekend");

        assertEquals("weekend_flag", bq.outputAlias());
        assertEquals("weekend_flag", sf.outputAlias());
        assertTrue(bq.bucketExpression().contains("EXTRACT(DAYOFWEEK FROM pickup_datetime)"));
        assertTrue(sf.bucketExpression().contains("DAYNAME(pickup_datetime)"));
        assertEquals("Weekend", extractThenLabel(bq.bucketExpression()));
        assertEquals("Weekend", extractThenLabel(sf.bucketExpression()));
    }

    @Test
    void hourDerivationKeepsSameCanonicalIntent() {
        TemporalDerivationEngine bqEngine = new TemporalDerivationEngine(new BigQueryDialect());
        TemporalDerivationEngine sfEngine = new TemporalDerivationEngine(new SnowflakeDialect());

        DerivedDimensionSpec bq = bqEngine.derive(SemanticConcept.HOUR_OF_DAY, "event_ts", "hour");
        DerivedDimensionSpec sf = sfEngine.derive(SemanticConcept.HOUR_OF_DAY, "event_ts", "hour");

        assertEquals("hour_of_day", bq.outputAlias());
        assertEquals("hour_of_day", sf.outputAlias());
        assertEquals("EXTRACT(HOUR FROM event_ts)", bq.bucketExpression());
        assertEquals("EXTRACT(HOUR FROM event_ts)", sf.bucketExpression());
    }

    private static String extractThenLabel(String expression) {
        int thenIdx = expression.indexOf("THEN '");
        int end = expression.indexOf("'", thenIdx + 6);
        return expression.substring(thenIdx + 6, end);
    }
}
