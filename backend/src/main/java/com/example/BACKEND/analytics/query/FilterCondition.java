package com.example.BACKEND.analytics.query;

import com.example.BACKEND.analytics.query.enums.Operator;

public class FilterCondition {

    private String field;      // canonical field name (e.g. event_name)
    private Operator operator; // EQUALS, IN, GT, LT
    private Object value;      // string, number, list

    public FilterCondition() {}

    // setters (needed for JSON + tests)
    public void setField(String field) {
        this.field = field;
    }

    public void setOperator(Operator operator) {
        this.operator = operator;
    }

    public void setValue(Object value) {
        this.value = value;
    }

    // getters
    public String getField() {
        return field;
    }

    public Operator getOperator() {
        return operator;
    }

    public Object getValue() {
        return value;
    }
}
