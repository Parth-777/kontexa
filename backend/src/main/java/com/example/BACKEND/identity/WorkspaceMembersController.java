package com.example.BACKEND.identity;

import com.example.BACKEND.identity.audit.AuditService;
import com.example.BACKEND.identity.auth.AuthContext;
import com.example.BACKEND.identity.auth.AuthContextHolder;
import com.example.BACKEND.identity.auth.AuthorizationService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Workspace member management — invite-based onboarding, role changes.
 * Replaces manual password assignment in UserAccessController over time.
 */
@RestController
@RequestMapping("/api/workspace/members")
public class WorkspaceMembersController {

    private final WorkspaceAuthService workspaceAuth;
    private final AuthorizationService authorization;
    private final AuditService audit;

    public WorkspaceMembersController(
            WorkspaceAuthService workspaceAuth,
            AuthorizationService authorization,
            AuditService audit
    ) {
        this.workspaceAuth = workspaceAuth;
        this.authorization = authorization;
        this.audit = audit;
    }

    @GetMapping
    public ResponseEntity<?> listMembers() {
        authorization.requireAdmin();
        AuthContext ctx = AuthContextHolder.require();
        List<Map<String, Object>> members = workspaceAuth.listMembers(ctx.workspaceId());
        return ResponseEntity.ok(Map.of("members", members));
    }

    @PutMapping("/{identityId}/role")
    public ResponseEntity<?> updateRole(
            @PathVariable UUID identityId,
            @RequestBody Map<String, String> body,
            HttpServletRequest request
    ) {
        authorization.requireAdmin();
        AuthContext ctx = AuthContextHolder.require();

        String role = body.get("role");
        if (role == null || role.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "role is required"));
        }

        workspaceAuth.updateMemberRole(ctx.workspaceId(), identityId, role.toUpperCase());

        audit.log(ctx.workspaceId(), ctx.identityId(), AuditService.ROLE_CHANGE,
                "target=" + identityId + " newRole=" + role,
                request.getRemoteAddr(), request.getHeader("User-Agent"));

        return ResponseEntity.ok(Map.of("message", "Role updated"));
    }

    @PutMapping("/{identityId}/status")
    public ResponseEntity<?> updateStatus(
            @PathVariable UUID identityId,
            @RequestBody Map<String, Object> body,
            HttpServletRequest request
    ) {
        authorization.requireAdmin();
        AuthContext ctx = AuthContextHolder.require();

        Object activeVal = body.get("active");
        if (activeVal == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "active is required"));
        }
        boolean active = Boolean.TRUE.equals(activeVal) || "true".equalsIgnoreCase(String.valueOf(activeVal));

        workspaceAuth.updateMemberStatus(ctx.workspaceId(), identityId, active);

        audit.log(ctx.workspaceId(), ctx.identityId(), AuditService.ROLE_CHANGE,
                "target=" + identityId + " active=" + active,
                request.getRemoteAddr(), request.getHeader("User-Agent"));

        return ResponseEntity.ok(Map.of("message", "Status updated"));
    }
}
