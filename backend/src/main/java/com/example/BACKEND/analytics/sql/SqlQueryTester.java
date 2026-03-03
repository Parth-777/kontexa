package com.example.BACKEND.analytics.sql;

import com.example.BACKEND.analytics.query.*;
import com.example.BACKEND.analytics.query.enums.*;
import com.example.BACKEND.analytics.sql.builder.SqlQueryBuilder;
import com.example.BACKEND.analytics.sql.model.SqlQuery;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

public class SqlQueryTester {

    public static void main(String[] args) {

        CanonicalQuery cql = new CanonicalQuery();

        // ENTITY
        cql.setEntity(EntityType.EVENT);

        // FILTER
        FilterCondition filter = new FilterCondition();
        filter.setField("event_name");
        filter.setOperator(Operator.EQUALS);
        filter.setValue("button_click");
        FilterCondition f2 = new FilterCondition();
        f2.setField("page_location");
        f2.setOperator(Operator.EQUALS);
        f2.setValue("/home");

        cql.setFilters(List.of(filter , f2));

        // METRIC
        Metric metric = new Metric();
        metric.setType(MetricType.COUNT);
        cql.setMetrics(List.of(metric));

        // GROUP BY
        cql.setGroupBy(List.of("event_name"));

        // LIMIT
        cql.setLimit(50);

        // BUILD SQL
        SqlQuery sql = SqlQueryBuilder.build(cql);
       // JdbcOperations jdbcTemplate;
       // JdbcTemplate jdbcTemplate  = new JdbcTemplate();
        //String db = jdbcTemplate.queryForObject("select current_database()", String.class);
        //System.out.println("DB = " + db);


        System.out.println("==== GENERATED SQL OBJECT ====");
        System.out.println(sql);
    }
}
