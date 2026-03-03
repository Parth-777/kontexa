package com.example.BACKEND.analytics.query.enums;

import com.fasterxml.jackson.annotation.JsonCreator;

public enum TimeRangeType {
    TODAY,
    YESTERDAY,
    LAST_7_DAYS,
    LAST_30_DAYS,
    CUSTOM,
    RELATIVE;

    @JsonCreator
    public static TimeRangeType from(String value) {
        return TimeRangeType.valueOf(value.toUpperCase());
    }
}
