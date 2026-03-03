package com.example.BACKEND.analytics.sql.translator;

import com.example.BACKEND.analytics.query.CanonicalQuery;
import com.example.BACKEND.analytics.query.Metric;
import com.example.BACKEND.analytics.query.enums.MetricType;
import com.example.BACKEND.analytics.sql.model.SqlQuery;

public class MetricTranslator {

    public static void apply(SqlQuery sql, CanonicalQuery cql) {

        // SAFETY: default metric
        if (cql.getMetrics() == null || cql.getMetrics().isEmpty()) {
            sql.addSelect("COUNT(*)");
            return;
        }

        for (Metric metric : cql.getMetrics()) {

            if (metric.getType() == null) {
                throw new IllegalStateException("Metric type cannot be null");
            }

            switch (metric.getType()) {
                case COUNT -> sql.addSelect("COUNT(*)");
                case SUM -> sql.addSelect("SUM(" + metric.getField() + ")");
                case AVG -> sql.addSelect("AVG(" + metric.getField() + ")");
                default -> throw new IllegalStateException("Unsupported metric type");
            }
        }
    }
}
