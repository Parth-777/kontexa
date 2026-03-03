package com.example.BACKEND.analytics.sql.translator;

import com.example.BACKEND.analytics.query.CanonicalQuery;
import com.example.BACKEND.analytics.sql.model.SqlQuery;

import java.util.ArrayList;
import java.util.List;

public class GroupByTranslator {

    public static void apply(SqlQuery sql, CanonicalQuery cql) {
        if (cql.getGroupBy() == null || cql.getGroupBy().isEmpty()) {
            return;
        }

        sql.getGroupBy().addAll(cql.getGroupBy());

        /*
         * IMPORTANT:
         * In SQL, every GROUP BY field must appear in SELECT
         * unless it's wrapped in an aggregate.
         * We add GROUP BY fields at the beginning of SELECT for proper SQL syntax.
         */
        List<String> fieldsToAdd = new ArrayList<>();
        for (String field : cql.getGroupBy()) {
            if (!sql.getSelect().contains(field)) {
                fieldsToAdd.add(field);
            }
        }
        
        // Add all GROUP BY fields at the beginning, before aggregates like COUNT(*)
        for (int i = fieldsToAdd.size() - 1; i >= 0; i--) {
            sql.getSelect().add(0, fieldsToAdd.get(i));
        }
    }
}
