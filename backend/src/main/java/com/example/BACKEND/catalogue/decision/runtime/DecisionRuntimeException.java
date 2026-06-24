package com.example.BACKEND.catalogue.decision.runtime;

public class DecisionRuntimeException extends RuntimeException {

    public DecisionRuntimeException(String message) {
        super(message);
    }

    public DecisionRuntimeException(String message, Throwable cause) {
        super(message, cause);
    }
}
