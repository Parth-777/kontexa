package com.example.BACKEND.analytics.query.enums;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

public enum EntityType {
    EVENT,
    USER;

    @JsonCreator
    public static EntityType from(String value) {
        return EntityType.valueOf(value.toUpperCase());
    }

    @JsonValue
    public String toValue() {
        return this.name();
    }
}
