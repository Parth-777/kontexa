package com.example.BACKEND.identity.auth;

/**
 * Thread-local holder for the current request's {@link AuthContext}.
 */
public final class AuthContextHolder {

    private static final ThreadLocal<AuthContext> CONTEXT = new ThreadLocal<>();

    private AuthContextHolder() {}

    public static void set(AuthContext context) {
        CONTEXT.set(context);
    }

    public static AuthContext get() {
        return CONTEXT.get();
    }

    public static AuthContext require() {
        AuthContext ctx = CONTEXT.get();
        if (ctx == null) {
            throw new UnauthorizedException("Authentication required");
        }
        return ctx;
    }

    public static void clear() {
        CONTEXT.remove();
    }
}
