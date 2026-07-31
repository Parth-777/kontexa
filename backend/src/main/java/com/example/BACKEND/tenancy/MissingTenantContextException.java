package com.example.BACKEND.tenancy;

/**
 * Thrown when authentication cannot resolve a valid {@link TenantContext}.
 * Mapped to HTTP 401 by the auth exception handler.
 */
public class MissingTenantContextException extends RuntimeException {

    public MissingTenantContextException(String message) {
        super(message);
    }
}
