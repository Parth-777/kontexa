package com.example.BACKEND.analytics.query;

import com.example.BACKEND.analytics.query.enums.MetricType;

public class Metric {

    private MetricType type;
    private String field; // required only for SUM / AVG

    public Metric() {}

    public MetricType getType() {
        return type;
    }
    public void setType(MetricType type) {
        this.type = type;
        //return this;
    }


    public String getField() {
        return field;
    }
    public void setField(String field) {
        this.field = field;
    }

}

