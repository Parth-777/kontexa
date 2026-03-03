package com.example.BACKEND.analytics.query;

import com.example.BACKEND.analytics.query.enums.TimeRangeType;

public class TimeRange {

    private     TimeRangeType type;
    private String value; // today, last_7_days

    public TimeRange() {}

    public TimeRangeType getType() {
        return type;
    }

    public void setType(TimeRangeType type) {
        this.type = type;
    }

    public String getValue() {
        return value;
    }

    public void setValue(String value) {
        this.value = value;
    }
}
