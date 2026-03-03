package com.example.BACKEND.controller;

import com.example.BACKEND.analytics.query.CanonicalQuery;
import com.example.BACKEND.analytics.query.builder.CqlIntentBuilder;
import com.example.BACKEND.analytics.query.intent.QueryIntent;
import com.example.BACKEND.analytics.query.parser.EnglishQueryParser;
import com.example.BACKEND.analytics.sql.builder.SqlQueryBuilder;
import com.example.BACKEND.analytics.sql.executor.SqlQueryExecutor;
import com.example.BACKEND.analytics.sql.generator.PostgresSqlGenerator;
import com.example.BACKEND.analytics.sql.model.SqlQuery;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/analytics")
public class AnalyticsQueryController {

    private final SqlQueryExecutor sqlQueryExecutor;

    public AnalyticsQueryController(SqlQueryExecutor sqlQueryExecutor) {
        this.sqlQueryExecutor = sqlQueryExecutor;
    }

    @PostMapping("/query")
    public List<Map<String, Object>> query(@RequestBody Map<String, String> body) {
        String english = body.get("query");

        QueryIntent intent = EnglishQueryParser.parse(english);
        CanonicalQuery cql = CqlIntentBuilder.build(intent);
        SqlQuery sqlQuery = SqlQueryBuilder.build(cql);
        String sql = PostgresSqlGenerator.generate(sqlQuery);

        System.out.println("EXECUTING SQL:");
        System.out.println(sql);

        return sqlQueryExecutor.execute(sql);
    }
}
