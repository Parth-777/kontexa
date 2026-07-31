package com.example.BACKEND.tenancy;

/**
 * Request-scoped holder for {@link TenantContext}.
 * Populated only by the authentication gate; cleared after the request.
 */
public final class TenantContextHolder {

    private static final ThreadLocal<TenantContext> CONTEXT = new ThreadLocal<>();

    private TenantContextHolder() {}

    public static void set(TenantContext context) {
        CONTEXT.set(context);
    }

    public static TenantContext get() {
        return CONTEXT.get();
    }

    public static TenantContext require() {
        TenantContext ctx = CONTEXT.get();
        if (ctx == null) {
            throw new MissingTenantContextException("TenantContext required");
        }
        return ctx;
    }

    public static void clear() {
        CONTEXT.remove();
    }
}
