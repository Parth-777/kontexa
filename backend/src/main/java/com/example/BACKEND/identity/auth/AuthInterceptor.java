package com.example.BACKEND.identity.auth;

import com.example.BACKEND.identity.session.SessionService;
import com.example.BACKEND.tenancy.MissingTenantContextException;
import com.example.BACKEND.tenancy.TenantContext;
import com.example.BACKEND.tenancy.TenantContextHolder;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Authentication middleware — validates Bearer session tokens and resolves
 * {@link TenantContext} once per request.
 *
 * All {@code /api/**} routes are fail-closed except explicitly public auth/onboarding paths.
 */
@Component
public class AuthInterceptor implements HandlerInterceptor {

    private static final Set<String> PUBLIC_PREFIXES = Set.of(
            "/api/auth/login",
            "/api/auth/refresh",
            "/api/auth/password-reset",
            "/api/onboarding",
            "/api/auth/tenant/login",
            "/api/auth/user/login",
            "/api/auth/mcp/exchange"
    );

    private static final Set<String> PUBLIC_EXACT_OR_PREFIX = Set.of(
            "/api/auth/invites/validate",
            "/api/auth/invites/accept"
    );

    private final SessionService sessions;
    private final ObjectMapper objectMapper;

    public AuthInterceptor(SessionService sessions, ObjectMapper objectMapper) {
        this.sessions = sessions;
        this.objectMapper = objectMapper;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws Exception {
        String path = request.getRequestURI();
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            return true;
        }

        boolean publicPath = isPublic(path);
        String token = extractBearer(request);

        if (publicPath) {
            if (token != null && sessions.isEnabled()) {
                // Best-effort bind for public paths that still benefit from context when present.
                bindAuthenticatedContext(token, response, false);
            }
            return true;
        }

        // Fail-closed: every non-public /api/** requires a valid session + TenantContext.
        if (path.startsWith("/api/")) {
            if (token == null) {
                writeUnauthorized(response, "Authentication required");
                return false;
            }
            if (!sessions.isEnabled()) {
                writeUnauthorized(response, "Authentication required");
                return false;
            }
            return bindAuthenticatedContext(token, response, true);
        }

        // Non-API paths (e.g. actuator already public): allow.
        return true;
    }

    /**
     * Resolves AuthContext + TenantContext once. Fail closed when the session is
     * present but TenantContext cannot be built.
     */
    private boolean bindAuthenticatedContext(
            String token,
            HttpServletResponse response,
            boolean rejectInvalid
    ) throws Exception {
        Optional<AuthContext> ctx = sessions.resolveAccessToken(token);
        if (ctx.isEmpty()) {
            if (rejectInvalid) {
                writeUnauthorized(response, "Invalid or expired session");
                return false;
            }
            return true;
        }
        try {
            TenantContext tenantContext = TenantContext.fromAuthContext(ctx.get());
            AuthContextHolder.set(ctx.get());
            TenantContextHolder.set(tenantContext);
            return true;
        } catch (MissingTenantContextException ex) {
            writeUnauthorized(response, ex.getMessage());
            return false;
        }
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response,
                                 Object handler, Exception ex) {
        TenantContextHolder.clear();
        AuthContextHolder.clear();
    }

    private boolean isPublic(String path) {
        for (String prefix : PUBLIC_EXACT_OR_PREFIX) {
            if (path.startsWith(prefix)) {
                return true;
            }
        }
        for (String prefix : PUBLIC_PREFIXES) {
            if (path.startsWith(prefix)) {
                return true;
            }
        }
        return path.equals("/") || path.startsWith("/actuator");
    }

    private String extractBearer(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        if (header != null && header.startsWith("Bearer ")) {
            return header.substring(7).trim();
        }
        return null;
    }

    private void writeUnauthorized(HttpServletResponse response, String message) throws Exception {
        response.setStatus(HttpStatus.UNAUTHORIZED.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        objectMapper.writeValue(response.getWriter(), Map.of("error", message));
    }
}
