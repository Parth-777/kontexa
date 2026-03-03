package com.example.BACKEND.analytics.sql;//package com.example.BACKEND.analytics.sql.test;

import com.example.BACKEND.analytics.query.*;
import com.example.BACKEND.analytics.query.enums.*;
import com.example.BACKEND.analytics.sql.builder.SqlQueryBuilder;
import com.example.BACKEND.analytics.sql.generator.PostgresSqlGenerator;
import com.example.BACKEND.analytics.sql.model.SqlQuery;

import java.util.List;

public class CqlToSqlTest {

    public static void main(String[] args) {

        // ---------- 1. INPUT CQL JSON (API CONTRACT) ----------
        String cqlJson = """
        {
          "entity": "EVENT",
          "filters": [
            {
              "field": "event_name",
              "operator": "EQUALS",
              "value": "Home Clicked"
            }
          ],
          "metrics": [
            {
              "type": "COUNT",
              "field": null
            }
          ],
          "groupBy": ["event_name"],
          "limit": 50,
          "schemaVersion": "V1",
          "timeRange": {
            "type": "LAST_7_DAYS",
            "value": "last_7_days"
          }
        }
        """;

        System.out.println("===== INPUT  JSON =====");
        System.out.println(cqlJson);

        // ---------- 2. BUILD CQL OBJECT ----------
        CanonicalQuery cql = new CanonicalQuery();

        cql.setEntity(EntityType.EVENT);
        cql.setSchemaVersion("V1");
        cql.setLimit(50);

        // Filter
        FilterCondition filter = new FilterCondition();
        filter.setField("event_name");
        filter.setOperator(Operator.EQUALS);
        filter.setValue("Home Clicked");
        cql.setFilters(List.of(filter));

        // Metric
        Metric metric = new Metric();
        metric.setType(MetricType.COUNT);
        cql.setMetrics(List.of(metric));

        // Group by
        cql.setGroupBy(List.of("event_name"));

        // Time range
        TimeRange timeRange = new TimeRange();
        timeRange.setType(TimeRangeType.LAST_7_DAYS);
        timeRange.setValue("last_30_days");
        cql.setTimeRange(timeRange);

        // ---------- 3. BUILD SQL QUERY ----------
        SqlQuery sqlQuery = SqlQueryBuilder.build(cql);

        // ---------- 4. GENERATE SQL ----------
        String sql = PostgresSqlGenerator.generate(sqlQuery);

        System.out.println("\n===== GENERATED SQL =====");
        System.out.println(sql);
    }
}
