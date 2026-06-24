package com.example.BACKEND.identity.auth;

import com.example.BACKEND.identity.session.SessionService;
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
 * Authentication middleware — validates Bearer session tokens.
 * Authorization (role checks) is handled separately in controllers/services.
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
            "/api/admin/identity/migrate"
    );

    /** Invite validate/accept are public; create/resend/revoke require admin session. */
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
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) return true;

        boolean publicPath = isPublic(path);
        if (!sessions.isEnabled()) return true; // sessions not migrated yet — permissive mode

        String token = extractBearer(request);
        if (token != null) {
            Optional<AuthContext> ctx = sessions.resolveAccessToken(token);
            if (ctx.isPresent()) {
                AuthContextHolder.set(ctx.get());
            } else if (!publicPath) {
                writeUnauthorized(response);
                return false;
            }
            return true;
        }

        if (publicPath) return true;
        return true; // backward compat: unauthenticated requests still pass during transition
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response,
                                 Object handler, Exception ex) {
        AuthContextHolder.clear();
    }

    private boolean isPublic(String path) {
        for (String prefix : PUBLIC_EXACT_OR_PREFIX) {
            if (path.startsWith(prefix)) return true;
        }
        for (String prefix : PUBLIC_PREFIXES) {
            if (path.startsWith(prefix)) return true;
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

    private void writeUnauthorized(HttpServletResponse response) throws Exception {
        response.setStatus(HttpStatus.UNAUTHORIZED.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        objectMapper.writeValue(response.getWriter(), Map.of("error", "Invalid or expired session"));
    }
}
