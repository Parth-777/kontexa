package com.example.BACKEND.analytics.query.enums;

import com.fasterxml.jackson.annotation.JsonCreator;

public enum Operator {
    EQUALS,
    NOT_EQUALS,
    GREATER_THAN,
    GREATER_THAN_EQUALS,
    LESS_THAN,
    LESS_THAN_EQUALS,
    IN,
    NOT_IN,
    CONTAINS;

    @JsonCreator
    public static Operator from(String value) {
        return Operator.valueOf(value.toUpperCase());
    }
}
