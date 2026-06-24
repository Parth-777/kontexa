package com.example.BACKEND.catalogue.agent.scale;

public class QueryRejectedException extends RuntimeException {

    public QueryRejectedException(String message) {
        super(message);
    }
}
