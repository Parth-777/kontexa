package com.example.BACKEND.identity.mcp;

import com.example.BACKEND.identity.IdentityRepository;
import com.example.BACKEND.identity.WorkspaceIdentityDomain.Identity;
import com.example.BACKEND.identity.auth.AuthContext;
import com.example.BACKEND.identity.auth.AuthContextHolder;
import com.example.BACKEND.identity.session.SessionService;
import com.example.BACKEND.identity.session.SessionTokens;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Browser → MCP login handoff (no OAuth, no PKCE, no JWT).
 *
 * <p>Reuses the existing username/password login and opaque-session model. The
 * MCP opens the existing {@code /signin} page in MCP mode; after a normal login
 * the page calls {@link #complete} (authenticated with the just-issued Bearer
 * token) to mint a single-use handoff code; the browser is redirected to the
 * MCP's localhost loopback with that code; the MCP then calls {@link #exchange}
 * to retrieve the real session tokens.
 *
 * <pre>
 * POST /api/auth/mcp/complete  (authenticated) — mint single-use code, return loopback redirect
 * POST /api/auth/mcp/exchange  (public)        — redeem code once, return session tokens
 * </pre>
 *
 * <p>Does not touch analytics, planners, CQM, catalogue logic, warehouse
 * execution, answer synthesis, or dashboard behaviour.
 */
@RestController
public class McpLoginController {

    private static final String MCP_DEVICE_LABEL = "Claude Desktop (MCP)";
    private static final String REQUIRED_CALLBACK_PATH = "/callback";
    private static final Set<String> LOOPBACK_HOSTS = Set.of(
            "127.0.0.1", "localhost", "::1", "[::1]"
    );

    private final SessionService sessions;
    private final IdentityRepository identities;
    private final McpHandoffRegistry handoffs;

    public McpLoginController(
            SessionService sessions,
            IdentityRepository identities,
            McpHandoffRegistry handoffs
    ) {
        this.sessions = sessions;
        this.identities = identities;
        this.handoffs = handoffs;
    }

    /**
     * Called by the login page immediately after a successful login while in MCP
     * mode. Requires the freshly issued Bearer token (so only the user who just
     * authenticated can mint a handoff code). Mints an independent MCP-labelled
     * session and returns a fully-formed loopback redirect URL carrying a
     * single-use code.
     */
    @PostMapping("/api/auth/mcp/complete")
    public ResponseEntity<?> complete(@RequestBody Map<String, String> body, HttpServletRequest request) {
        AuthContext ctx = AuthContextHolder.require(); // → 401 if unauthenticated

        String redirectUri = body.get("redirectUri");
        String state = body.get("state");
        if (redirectUri == null || redirectUri.isBlank() || state == null || state.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "redirectUri and state are required"));
        }

        URI parsed = validateLoopbackRedirect(redirectUri);
        if (parsed == null) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "redirectUri must be an http loopback URL ending in /callback"));
        }

        if (ctx.identityId() == null || ctx.workspaceId() == null) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("error", "No active workspace for this session"));
        }

        Optional<Identity> identityOpt = identities.findIdentityById(ctx.identityId());
        if (identityOpt.isEmpty()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("error", "Identity not found"));
        }

        // Mint an INDEPENDENT MCP session for the same identity + workspace, so it
        // is separately revocable from the web session.
        SessionTokens tokens = sessions.createSession(
                identityOpt.get(),
                ctx.workspaceId(),
                MCP_DEVICE_LABEL,
                request.getHeader("User-Agent"),
                request.getRemoteAddr()
        );

        String code = handoffs.issue(tokens, state, identitySummary(ctx));
        String target = buildCallbackUrl(parsed, code, state);
        return ResponseEntity.ok(Map.of("redirectUri", target));
    }

    /**
     * Called by the MCP process (server-to-server, no browser). Redeems the
     * single-use code and returns the session tokens in the same shape as the
     * normal login response.
     */
    @PostMapping("/api/auth/mcp/exchange")
    public ResponseEntity<?> exchange(@RequestBody Map<String, String> body) {
        String code = body.get("code");
        if (code == null || code.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "code is required"));
        }

        Optional<McpHandoffRegistry.Handoff> handoffOpt = handoffs.redeem(code);
        if (handoffOpt.isEmpty()) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(Map.of("error", "Invalid or expired handoff code"));
        }

        McpHandoffRegistry.Handoff handoff = handoffOpt.get();
        SessionTokens tokens = handoff.tokens();

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("accessToken", tokens.accessToken());
        response.put("refreshToken", tokens.refreshToken());
        response.put("accessExpiresAt", tokens.accessExpiresAt().toString());
        response.put("refreshExpiresAt", tokens.refreshExpiresAt().toString());
        response.putAll(handoff.identitySummary());
        return ResponseEntity.ok(response);
    }

    private Map<String, Object> identitySummary(AuthContext ctx) {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("identityId", ctx.identityId() != null ? ctx.identityId().toString() : "");
        summary.put("workspaceId", ctx.workspaceId() != null ? ctx.workspaceId().toString() : "");
        summary.put("workspaceSlug", ctx.workspaceSlug() != null ? ctx.workspaceSlug() : "");
        summary.put("email", ctx.email());
        summary.put("displayName", ctx.displayName());
        summary.put("role", ctx.role());
        return summary;
    }

    /**
     * Validate that the redirect target is a localhost loopback callback. This is
     * the key guard that replaces OAuth redirect-URI registration: only loopback
     * hosts on path {@code /callback} over plain http are accepted.
     */
    private URI validateLoopbackRedirect(String redirectUri) {
        try {
            URI uri = new URI(redirectUri);
            if (!"http".equalsIgnoreCase(uri.getScheme())) {
                return null;
            }
            String host = uri.getHost();
            if (host == null || !LOOPBACK_HOSTS.contains(host.toLowerCase())) {
                return null;
            }
            if (!REQUIRED_CALLBACK_PATH.equals(uri.getPath())) {
                return null;
            }
            return uri;
        } catch (URISyntaxException e) {
            return null;
        }
    }

    private String buildCallbackUrl(URI base, String code, String state) {
        StringBuilder sb = new StringBuilder();
        sb.append(base.getScheme()).append("://").append(base.getHost());
        if (base.getPort() != -1) {
            sb.append(':').append(base.getPort());
        }
        sb.append(base.getPath());
        sb.append("?code=").append(URLEncoder.encode(code, StandardCharsets.UTF_8));
        sb.append("&state=").append(URLEncoder.encode(state, StandardCharsets.UTF_8));
        return sb.toString();
    }
}
