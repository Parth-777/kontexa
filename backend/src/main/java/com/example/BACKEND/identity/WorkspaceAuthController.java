package com.example.BACKEND.identity;

import com.example.BACKEND.identity.audit.AuditService;
import com.example.BACKEND.identity.auth.*;
import com.example.BACKEND.identity.invite.InviteService;
import com.example.BACKEND.identity.password.PasswordResetService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Enterprise authentication API.
 *
 * POST /api/auth/login              — unified workspace login
 * POST /api/auth/refresh             — refresh access token
 * POST /api/auth/logout              — revoke session
 * POST /api/auth/switch-workspace    — change active workspace context
 * GET  /api/auth/workspaces          — list workspaces for current identity
 * POST /api/auth/invites              — admin invite user
 * GET  /api/auth/invites/validate    — validate invite token
 * POST /api/auth/invites/accept       — accept invite + set password
 * POST /api/auth/password-reset/request
 * POST /api/auth/password-reset/confirm
 */
@RestController
public class WorkspaceAuthController {

    private final AuthenticationService authentication;
    private final AuthorizationService authorization;
    private final InviteService invites;
    private final PasswordResetService passwordReset;
    private final IdentityAuthResolver identityResolver;
    private final WorkspaceAuthService workspaceAuthService;

    public WorkspaceAuthController(
            AuthenticationService authentication,
            AuthorizationService authorization,
            InviteService invites,
            PasswordResetService passwordReset,
            IdentityAuthResolver identityResolver,
            WorkspaceAuthService workspaceAuthService
    ) {
        this.authentication = authentication;
        this.authorization = authorization;
        this.invites = invites;
        this.passwordReset = passwordReset;
        this.identityResolver = identityResolver;
        this.workspaceAuthService = workspaceAuthService;
    }

    @PostMapping("/api/auth/login")
    public ResponseEntity<?> login(@RequestBody Map<String, String> body, HttpServletRequest request) {
        // Workspace ID = login identifier (admin: former tenant id, user: former user id)
        String workspaceId  = coalesce(body.get("workspaceId"), body.get("userId"), body.get("email"));
        String password     = body.get("password");
        String authProvider = body.get("authProvider");

        if (workspaceId == null || workspaceId.isBlank() || password == null || password.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "workspaceId and password are required"));
        }

        Optional<Map<String, Object>> result = authentication.login(
                workspaceId.trim(), password.trim(),
                workspaceId.trim(),
                authProvider,
                body.get("deviceLabel"),
                request.getHeader("User-Agent"),
                request.getRemoteAddr()
        );

        return result.<ResponseEntity<?>>map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                        .body(Map.of("error", "Invalid credentials")));
    }

    @PostMapping("/api/auth/refresh")
    public ResponseEntity<?> refresh(@RequestBody Map<String, String> body) {
        String refreshToken = body.get("refreshToken");
        if (refreshToken == null || refreshToken.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "refreshToken is required"));
        }
        return authentication.refresh(refreshToken)
                .<ResponseEntity<?>>map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                        .body(Map.of("error", "Invalid or expired refresh token")));
    }

    @PostMapping("/api/auth/logout")
    public ResponseEntity<?> logout(@RequestBody Map<String, String> body, HttpServletRequest request) {
        String accessToken = extractBearer(body, request);
        AuthContext ctx = AuthContextHolder.get();
        UUID workspaceId = ctx != null ? ctx.workspaceId() : null;
        UUID identityId  = ctx != null ? ctx.identityId() : null;
        authentication.logout(accessToken, workspaceId, identityId,
                request.getRemoteAddr(), request.getHeader("User-Agent"));
        return ResponseEntity.ok(Map.of("message", "Logged out"));
    }

    @PostMapping("/api/auth/switch-workspace")
    public ResponseEntity<?> switchWorkspace(@RequestBody Map<String, String> body, HttpServletRequest request) {
        AuthContext ctx = AuthContextHolder.require();
        String workspaceSlug = body.get("workspaceSlug");
        if (workspaceSlug == null || workspaceSlug.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "workspaceSlug is required"));
        }

        return authentication.switchWorkspace(
                ctx.sessionId(), ctx.identityId(), workspaceSlug.trim(),
                request.getRemoteAddr(), request.getHeader("User-Agent"))
                .<ResponseEntity<?>>map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.status(HttpStatus.FORBIDDEN)
                        .body(Map.of("error", "You do not have access to this workspace")));
    }

    @GetMapping("/api/auth/workspaces")
    public ResponseEntity<?> listWorkspaces(@RequestParam(required = false) String email) {
        String resolvedEmail = email;
        AuthContext ctx = AuthContextHolder.get();
        if ((resolvedEmail == null || resolvedEmail.isBlank()) && ctx != null) {
            resolvedEmail = ctx.email();
        }
        if (resolvedEmail == null || resolvedEmail.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "email is required"));
        }
        return ResponseEntity.ok(Map.of(
                "workspaces", identityResolver.listWorkspacesForEmail(resolvedEmail.trim())
        ));
    }

    @PostMapping("/api/auth/invites")
    public ResponseEntity<?> createInvite(@RequestBody Map<String, String> body, HttpServletRequest request) {
        authorization.requireAdmin();
        AuthContext ctx = AuthContextHolder.require();

        String email = body.get("email");
        String role  = body.get("role");
        if (email == null || email.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "email is required"));
        }

        try {
            Map<String, Object> invite = invites.createInvite(
                    ctx.workspaceId(), email, role, ctx.identityId(),
                    request.getRemoteAddr(), request.getHeader("User-Agent"));
            return ResponseEntity.ok(invite);
        } catch (IllegalArgumentException | IllegalStateException ex) {
            return ResponseEntity.badRequest().body(Map.of("error", ex.getMessage()));
        }
    }

    @PostMapping("/api/auth/invites/{inviteId}/resend")
    public ResponseEntity<?> resendInvite(
            @PathVariable UUID inviteId,
            HttpServletRequest request
    ) {
        authorization.requireAdmin();
        AuthContext ctx = AuthContextHolder.require();
        try {
            Map<String, Object> result = invites.resendInvite(
                    ctx.workspaceId(), inviteId, ctx.identityId(),
                    request.getRemoteAddr(), request.getHeader("User-Agent"));
            return ResponseEntity.ok(result);
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest().body(Map.of("error", ex.getMessage()));
        }
    }

    @PostMapping("/api/auth/invites/{inviteId}/revoke")
    public ResponseEntity<?> revokeInvite(
            @PathVariable UUID inviteId,
            HttpServletRequest request
    ) {
        authorization.requireAdmin();
        AuthContext ctx = AuthContextHolder.require();
        try {
            invites.revokeInvite(ctx.workspaceId(), inviteId, ctx.identityId(),
                    request.getRemoteAddr(), request.getHeader("User-Agent"));
            return ResponseEntity.ok(Map.of("message", "Invitation revoked"));
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest().body(Map.of("error", ex.getMessage()));
        }
    }

    @GetMapping("/api/auth/invites/validate")
    public ResponseEntity<?> validateInvite(@RequestParam String token) {
        return invites.validateToken(token)
                .<ResponseEntity<?>>map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(Map.of("valid", false, "error", "Invite is invalid or expired")));
    }

    @PostMapping("/api/auth/invites/accept")
    public ResponseEntity<?> acceptInvite(@RequestBody Map<String, String> body, HttpServletRequest request) {
        String token = body.get("token");
        String password = body.get("password");
        String displayName = body.get("displayName");
        if (token == null || password == null || password.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "token and password are required"));
        }
        try {
            invites.acceptInvite(token, displayName, password,
                    request.getRemoteAddr(), request.getHeader("User-Agent"));
            return ResponseEntity.ok(Map.of(
                    "message", "Account activated. You can now sign in.",
                    "redirectTo", "/signin"
            ));
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest().body(Map.of("error", ex.getMessage()));
        }
    }

    @PostMapping("/api/auth/password-reset/request")
    public ResponseEntity<?> requestPasswordReset(@RequestBody Map<String, String> body, HttpServletRequest request) {
        String email = body.get("email");
        if (email == null || email.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "email is required"));
        }
        Map<String, String> result = passwordReset.requestReset(email.trim(),
                request.getRemoteAddr(), request.getHeader("User-Agent"))
                .orElse(Map.of("message", "If the account exists, a reset link has been generated."));
        return ResponseEntity.ok(result);
    }

    @PostMapping("/api/auth/password-reset/confirm")
    public ResponseEntity<?> confirmPasswordReset(@RequestBody Map<String, String> body, HttpServletRequest request) {
        String token = body.get("token");
        String password = body.get("password");
        if (token == null || password == null || password.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "token and password are required"));
        }
        try {
            passwordReset.confirmReset(token, password,
                    request.getRemoteAddr(), request.getHeader("User-Agent"));
            return ResponseEntity.ok(Map.of("message", "Password updated successfully"));
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest().body(Map.of("error", ex.getMessage()));
        }
    }

    @GetMapping("/api/workspace/status")
    public ResponseEntity<?> getStatus() {
        WorkspaceIdentityDomain.MigrationStatus status = workspaceAuthService.getMigrationStatus();
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("identityTablesExist", status.identityTablesExist());
        response.put("overallStatus", status.overallStatus());
        response.put("migratedWorkspaces", status.migratedWorkspaces());
        response.put("migratedIdentities", status.migratedIdentities());
        response.put("dualReadActive", status.identityTablesExist() && !"COMPLETED".equals(status.overallStatus()));
        return ResponseEntity.ok(response);
    }

    private String coalesce(String... values) {
        for (String v : values) {
            if (v != null && !v.isBlank()) return v;
        }
        return null;
    }

    private String extractBearer(Map<String, String> body, HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        if (header != null && header.startsWith("Bearer ")) return header.substring(7).trim();
        return body.get("accessToken");
    }
}
