package com.example.BACKEND.analytics.sql.translator;

import com.example.BACKEND.analytics.query.CanonicalQuery;
import com.example.BACKEND.analytics.query.enums.TimeRangeType;
import com.example.BACKEND.analytics.sql.model.SqlQuery;

public class TimeRangeTranslator {

    public static void apply(SqlQuery sql, CanonicalQuery cql) {
        if (cql.getTimeRange() == null) return;

        String condition = switch (cql.getTimeRange().getType()) {
            case TODAY -> "event_time >= CURRENT_DATE";
            case YESTERDAY -> null;
            case LAST_7_DAYS -> "event_time >= now() - interval '7 days'";
            case LAST_30_DAYS -> "event_time >= now() - interval '30 days'";
            case CUSTOM -> null;
            case RELATIVE -> {
                // Use the value stored in TimeRange (number of days)
                String days = cql.getTimeRange().getValue();
                if (days != null && !days.isEmpty()) {
                    yield "event_time >= now() - interval '" + days + " days'";
                } else {
                    yield null;
                }
            }
        };

        // Only add condition if it's not null
        if (condition != null) {
            sql.getWhere().add(condition);
        }
    }
}
