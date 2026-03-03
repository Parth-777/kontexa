package com.example.BACKEND.analytics.sql.translator;

import com.example.BACKEND.analytics.query.CanonicalQuery;
import com.example.BACKEND.analytics.query.FilterCondition;
import com.example.BACKEND.analytics.query.enums.Operator;
import com.example.BACKEND.analytics.sql.model.SqlQuery;

public class FilterTranslator {

    public static void apply(SqlQuery sql, CanonicalQuery cql) {
        if (cql.getFilters() == null) return;

        for (FilterCondition f : cql.getFilters()) {
            sql.getWhere().add(buildCondition(f));
        }
    }

    private static String buildCondition(FilterCondition f) {
        return switch (f.getOperator()) {
            case EQUALS -> f.getField() + " = '" + f.getValue() + "'";
            case NOT_EQUALS -> f.getField() + " != '" + f.getValue() + "'";
            case CONTAINS -> f.getField() + " LIKE '%" + f.getValue() + "%'";
            case GREATER_THAN -> f.getField() + " > '" + f.getValue() + "'";
            case GREATER_THAN_EQUALS -> null;
            case LESS_THAN -> f.getField() + " < '" + f.getValue() + "'";
            case LESS_THAN_EQUALS -> null;
            case IN -> null;
            case NOT_IN -> null;
        };
    }
}
