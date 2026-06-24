package com.example.BACKEND.catalogue.decision.compute;

public class ComputeException extends RuntimeException {

    public ComputeException(String message) {
        super(message);
    }

    public ComputeException(String message, Throwable cause) {
        super(message, cause);
    }
}
