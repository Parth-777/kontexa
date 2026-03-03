package com.example.BACKEND.analytics.query.enums;

import com.fasterxml.jackson.annotation.JsonCreator;

public enum MetricType {
    COUNT,
    SUM,
    AVG;

    @JsonCreator
    public static MetricType from(String value) {
        return MetricType.valueOf(value.toUpperCase());
    }
}
