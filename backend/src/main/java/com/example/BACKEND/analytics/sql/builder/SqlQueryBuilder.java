package com.example.BACKEND.analytics.sql.builder;

import com.example.BACKEND.analytics.query.CanonicalQuery;
import com.example.BACKEND.analytics.query.enums.EntityType;
import com.example.BACKEND.analytics.query.enums.MetricType;
import com.example.BACKEND.analytics.sql.model.SqlQuery;
import com.example.BACKEND.analytics.sql.resolver.EntityTableResolver;
import com.example.BACKEND.analytics.sql.translator.FilterTranslator;
import com.example.BACKEND.analytics.sql.translator.GroupByTranslator;
import com.example.BACKEND.analytics.sql.translator.MetricTranslator;
import com.example.BACKEND.analytics.sql.translator.TimeRangeTranslator;

public class SqlQueryBuilder {

    public static SqlQuery build(CanonicalQuery cql) {
        if (cql.getEntity() == EntityType.USER &&
                (hasEventFilters(cql) || hasEventMetrics(cql))) {

            throw new IllegalStateException(
                    "Invalid query: EVENT fields used with USER entity"
            );
        }

        SqlQuery sql = new SqlQuery();

        // 1. FROM
        sql.setFrom(EntityTableResolver.resolve(cql.getEntity()));

        System.out.println(sql.getFrom())
;

        String table = EntityTableResolver.resolve(cql.getEntity());
        if (table == null) {
            throw new IllegalStateException("Resolved table name is null");
        }
        sql.setFrom(table);

        // 2. METRICS (SELECT)
        MetricTranslator.apply(sql, cql);

        // 3. FILTERS (WHERE)
        FilterTranslator.apply(sql, cql);

        // 4. TIME RANGE (WHERE)
        TimeRangeTranslator.apply(sql, cql);



        // 5. GROUP BY
        GroupByTranslator.apply(sql, cql);


        // 6. LIMIT
        sql.setLimit(cql.getLimit());

        return sql;
    }
    private static boolean hasEventFilters(CanonicalQuery cql) {
        return cql.getFilters() != null &&
                cql.getFilters().stream().anyMatch(f ->
                        f.getField().equals("event_name") ||
                                f.getField().equals("event_time") ||
                                f.getField().equals("page_location")
                );
    }

    private static boolean hasEventMetrics(CanonicalQuery cql) {
        return cql.getMetrics() != null &&
                cql.getMetrics().stream().anyMatch(m ->
                        m.getType() == MetricType.COUNT
                );
    }

}
