package com.example.BACKEND.controller;

import com.example.BACKEND.analytics.query.CanonicalQuery;
import com.example.BACKEND.analytics.query.builder.CqlIntentBuilder;
import com.example.BACKEND.analytics.query.intent.QueryIntent;
import com.example.BACKEND.analytics.query.parser.EnglishQueryParser;
import com.example.BACKEND.analytics.sql.builder.SqlQueryBuilder;
import com.example.BACKEND.analytics.sql.executor.SqlQueryExecutor;
import com.example.BACKEND.analytics.sql.generator.PostgresSqlGenerator;
import com.example.BACKEND.analytics.sql.model.SqlQuery;
import com.example.BACKEND.identity.auth.AuthorizationService;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * Legacy shared-DB analytics query. Local profile only + admin gate.
 * Production traffic must use tenant-scoped decision/catalogue paths.
 */
@Profile("local")
@RestController
@RequestMapping("/api/analytics")
public class AnalyticsQueryController {

    private final SqlQueryExecutor sqlQueryExecutor;
    private final AuthorizationService authorization;

    public AnalyticsQueryController(SqlQueryExecutor sqlQueryExecutor, AuthorizationService authorization) {
        this.sqlQueryExecutor = sqlQueryExecutor;
        this.authorization = authorization;
    }

    @PostMapping("/query")
    public List<Map<String, Object>> query(@RequestBody Map<String, String> body) {
        authorization.requireAdmin();
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
