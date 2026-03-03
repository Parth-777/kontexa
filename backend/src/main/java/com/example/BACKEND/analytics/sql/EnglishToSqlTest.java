package com.example.BACKEND.analytics.sql;


import com.example.BACKEND.analytics.query.CanonicalQuery;
import com.example.BACKEND.analytics.query.builder.CqlIntentBuilder;
import com.example.BACKEND.analytics.query.intent.QueryIntent;
import com.example.BACKEND.analytics.query.parser.EnglishQueryParser;
import com.example.BACKEND.analytics.sql.builder.SqlQueryBuilder;
import com.example.BACKEND.analytics.sql.generator.PostgresSqlGenerator;
import com.example.BACKEND.analytics.sql.model.SqlQuery;

public class EnglishToSqlTest {
    public static void main(String [] args){
        String english =
                "what users clicked on a button in last 7 days";

        QueryIntent intent =
                EnglishQueryParser.parse(english);

        CanonicalQuery cql =
                CqlIntentBuilder.build(intent);

        SqlQuery sql =
                SqlQueryBuilder.build(cql);

        String postgresSql =
                PostgresSqlGenerator.generate(sql);
        System.out.println("=====Input =====");
        System.out.println(english);
        System.out.println();
        System.out.println("===Generated Sql ===");
        System.out.println(postgresSql);


    }
}
