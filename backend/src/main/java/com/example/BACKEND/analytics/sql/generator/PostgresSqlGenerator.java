package com.example.BACKEND.analytics.sql.generator;

import com.example.BACKEND.analytics.sql.model.SqlQuery;

public class PostgresSqlGenerator {

    public static String generate(SqlQuery q) {
        StringBuilder sb = new StringBuilder();

        sb.append("SELECT ")
                .append(String.join(", ", q.getSelect()))
                .append(" FROM ")
                .append(q.getFrom());

        if (!q.getWhere().isEmpty()) {
            sb.append(" WHERE ")
                    .append(String.join(" AND ", q.getWhere()));
        }

        if (!q.getGroupBy().isEmpty()) {
            sb.append(" GROUP BY ")
                    .append(String.join(", ", q.getGroupBy()));
        }

        if (q.getLimit() != null) {
            sb.append(" LIMIT ").append(q.getLimit());
        }

        return sb.toString();
    }
}
