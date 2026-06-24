package com.example.BACKEND.catalogue.decision.registry;

public class RegistryException extends RuntimeException {

    public RegistryException(String message) {
        super(message);
    }

    public RegistryException(String message, Throwable cause) {
        super(message, cause);
    }
}
