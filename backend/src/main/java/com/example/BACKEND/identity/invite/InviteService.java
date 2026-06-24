package com.example.BACKEND.identity.invite;

import com.example.BACKEND.identity.IdentityRepository;
import com.example.BACKEND.identity.WorkspaceIdentityDomain.Identity;
import com.example.BACKEND.identity.WorkspaceIdentityDomain.Role;
import com.example.BACKEND.identity.audit.AuditService;
import com.example.BACKEND.identity.auth.PasswordService;
import com.example.BACKEND.identity.auth.TokenHasher;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class InviteService {

    public static final String STATUS_INVITED   = "INVITED";
    public static final String STATUS_ACTIVE    = "ACTIVE";
    public static final String STATUS_EXPIRED   = "EXPIRED";
    public static final String STATUS_REVOKED   = "REVOKED";
    public static final String STATUS_SUSPENDED = "SUSPENDED";

    private final InviteRepository invites;
    private final IdentityRepository identities;
    private final TokenHasher tokenHasher;
    private final PasswordService passwords;
    private final AuditService audit;
    private final InviteEmailService emailService;

    private final int inviteTtlHours;
    private final String frontendBaseUrl;

    public InviteService(
            InviteRepository invites,
            IdentityRepository identities,
            TokenHasher tokenHasher,
            PasswordService passwords,
            AuditService audit,
            InviteEmailService emailService,
            @Value("${kontexa.auth.invite.ttl-hours:72}") int inviteTtlHours,
            @Value("${kontexa.auth.frontend-base-url:http://localhost:3000}") String frontendBaseUrl
    ) {
        this.invites = invites;
        this.identities = identities;
        this.tokenHasher = tokenHasher;
        this.passwords = passwords;
        this.audit = audit;
        this.emailService = emailService;
        this.inviteTtlHours = inviteTtlHours;
        this.frontendBaseUrl = frontendBaseUrl;
    }

    public boolean isEnabled() {
        return invites.isEnabled();
    }

    @Transactional
    public Map<String, Object> createInvite(
            UUID workspaceId,
            String email,
            String role,
            UUID invitedBy,
            String ipAddress,
            String userAgent
    ) {
        requireEnabled();
        String normalizedEmail = email.trim().toLowerCase();
        String normalizedRole = normalizeRole(role);

        Optional<Identity> existing = identities.findIdentityByWorkspaceAndEmail(workspaceId, normalizedEmail);
        if (existing.isPresent() && STATUS_ACTIVE.equals(existing.get().status())
                && identities.hasActiveMembership(workspaceId, existing.get().id())) {
            throw new IllegalArgumentException("This user is already an active workspace member");
        }

        UUID identityId = identities.createOrRefreshInvitedIdentity(workspaceId, normalizedEmail);
        invites.revokePendingTokensForIdentity(identityId);

        String rawToken = generateRawToken();
        LocalDateTime expires = LocalDateTime.now().plusHours(inviteTtlHours);

        UUID inviteId = invites.insertToken(
                workspaceId, identityId, tokenHasher.hash(rawToken), invitedBy, normalizedRole, expires);

        String workspaceName = identities.findWorkspaceById(workspaceId)
                .map(w -> w.name())
                .orElse("your workspace");
        String inviterName = identities.findIdentityById(invitedBy)
                .map(Identity::displayName)
                .orElse("Workspace admin");

        String activationUrl = frontendBaseUrl + "/activate-invite?token=" + rawToken;
        boolean emailSent = emailService.sendInviteEmail(
                normalizedEmail, workspaceName, inviterName, activationUrl, expires);

        audit.log(workspaceId, invitedBy, AuditService.INVITE_SENT,
                "email=" + normalizedEmail + " role=" + normalizedRole + " inviteId=" + inviteId,
                ipAddress, userAgent);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("inviteId", inviteId.toString());
        result.put("identityId", identityId.toString());
        result.put("email", normalizedEmail);
        result.put("role", normalizedRole);
        result.put("expiresAt", expires.toString());
        result.put("lifecycleStatus", STATUS_INVITED);
        result.put("emailSent", emailSent);
        return result;
    }

    @Transactional
    public Map<String, Object> resendInvite(
            UUID workspaceId,
            UUID inviteId,
            UUID resentBy,
            String ipAddress,
            String userAgent
    ) {
        requireEnabled();
        Map<String, Object> invite = invites.findById(inviteId, workspaceId)
                .orElseThrow(() -> new IllegalArgumentException("Invite not found"));

        if (invite.get("accepted_at") != null) {
            throw new IllegalArgumentException("Invite has already been accepted");
        }
        if (invite.get("revoked_at") != null) {
            throw new IllegalArgumentException("Invite has been revoked");
        }

        UUID identityId = UUID.fromString(String.valueOf(invite.get("identity_id")));
        String email = String.valueOf(invite.get("email"));
        String role = String.valueOf(invite.get("role"));

        invites.revokePendingTokensForIdentity(identityId);

        String rawToken = generateRawToken();
        LocalDateTime expires = LocalDateTime.now().plusHours(inviteTtlHours);

        UUID newInviteId = invites.insertToken(
                workspaceId, identityId, tokenHasher.hash(rawToken), resentBy, role, expires);

        identities.updateIdentityStatus(identityId, STATUS_INVITED);

        String workspaceName = identities.findWorkspaceById(workspaceId)
                .map(w -> w.name())
                .orElse("your workspace");
        String inviterName = identities.findIdentityById(resentBy)
                .map(Identity::displayName)
                .orElse("Workspace admin");

        String activationUrl = frontendBaseUrl + "/activate-invite?token=" + rawToken;
        boolean emailSent = emailService.sendInviteEmail(email, workspaceName, inviterName, activationUrl, expires);

        audit.log(workspaceId, resentBy, AuditService.INVITE_RESENT,
                "email=" + email + " inviteId=" + newInviteId, ipAddress, userAgent);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("inviteId", newInviteId.toString());
        result.put("identityId", identityId.toString());
        result.put("email", email);
        result.put("role", role);
        result.put("expiresAt", expires.toString());
        result.put("lifecycleStatus", STATUS_INVITED);
        result.put("emailSent", emailSent);
        return result;
    }

    @Transactional
    public void revokeInvite(UUID workspaceId, UUID inviteId, UUID revokedBy,
                             String ipAddress, String userAgent) {
        requireEnabled();
        Map<String, Object> invite = invites.findById(inviteId, workspaceId)
                .orElseThrow(() -> new IllegalArgumentException("Invite not found"));

        if (invite.get("accepted_at") != null) {
            throw new IllegalArgumentException("Cannot revoke an accepted invite");
        }

        invites.markRevoked(inviteId);
        UUID identityId = UUID.fromString(String.valueOf(invite.get("identity_id")));
        identities.updateIdentityStatus(identityId, STATUS_REVOKED);

        audit.log(workspaceId, revokedBy, AuditService.INVITE_REVOKED,
                "email=" + invite.get("email") + " inviteId=" + inviteId, ipAddress, userAgent);
    }

    public Optional<Map<String, Object>> validateToken(String rawToken) {
        if (!isEnabled() || rawToken == null || rawToken.isBlank()) return Optional.empty();
        return invites.findValidByTokenHash(tokenHasher.hash(rawToken.trim()))
                .map(this::toPublicInviteInfo);
    }

    @Transactional
    public void acceptInvite(String rawToken, String displayName, String password,
                             String ipAddress, String userAgent) {
        var inviteOpt = invites.findValidByTokenHash(tokenHasher.hash(rawToken.trim()));
        if (inviteOpt.isEmpty()) {
            throw new IllegalArgumentException("Invite is invalid or expired");
        }

        Map<String, Object> invite = inviteOpt.get();
        UUID workspaceId = UUID.fromString(String.valueOf(invite.get("workspace_id")));
        UUID identityId = UUID.fromString(String.valueOf(invite.get("identity_id")));
        String email = String.valueOf(invite.get("email"));
        String role = String.valueOf(invite.get("role"));
        UUID inviteId = UUID.fromString(String.valueOf(invite.get("id")));

        String resolvedName = displayName != null && !displayName.isBlank()
                ? displayName.trim()
                : String.valueOf(invite.get("display_name"));

        identities.activateIdentity(identityId, resolvedName, passwords.hash(password));
        identities.upsertMembership(workspaceId, identityId, role);
        invites.markAccepted(inviteId);
        invites.revokePendingTokensForIdentity(identityId);

        audit.log(workspaceId, identityId, AuditService.INVITE_ACCEPTED,
                "email=" + email + " role=" + role, ipAddress, userAgent);
    }

    public List<Map<String, Object>> listTeamMembers(UUID workspaceId) {
        if (!isEnabled()) {
            return identities.listMembersForWorkspace(workspaceId).stream()
                    .map(this::legacyMemberRow)
                    .collect(Collectors.toList());
        }
        return invites.listTeamWithInvites(workspaceId).stream()
                .map(this::teamMemberRow)
                .collect(Collectors.toList());
    }

    private Map<String, Object> toPublicInviteInfo(Map<String, Object> row) {
        Map<String, Object> safe = new LinkedHashMap<>();
        safe.put("valid", true);
        safe.put("email", row.get("email"));
        safe.put("displayName", row.get("display_name"));
        safe.put("role", row.get("role"));
        safe.put("workspaceName", row.get("workspace_name"));
        safe.put("workspaceSlug", row.get("slug"));
        safe.put("inviterName", row.get("inviter_name"));
        safe.put("expiresAt", String.valueOf(row.get("expires_at")));
        return safe;
    }

    private Map<String, Object> teamMemberRow(Map<String, Object> row) {
        String lifecycle = deriveLifecycleStatus(row);
        Map<String, Object> member = new LinkedHashMap<>();
        member.put("identityId", String.valueOf(row.get("identity_id")));
        member.put("email", row.get("email"));
        member.put("displayName", row.get("display_name"));
        member.put("role", row.get("role"));
        member.put("lifecycleStatus", lifecycle);
        member.put("inviteId", row.get("invite_id") != null ? String.valueOf(row.get("invite_id")) : null);
        member.put("expiresAt", row.get("expires_at") != null ? String.valueOf(row.get("expires_at")) : null);
        member.put("invitedAt", row.get("invited_at") != null ? String.valueOf(row.get("invited_at")) : null);
        member.put("invitedByName", row.get("invited_by_name"));
        return member;
    }

    private Map<String, Object> legacyMemberRow(Map<String, Object> row) {
        String status = String.valueOf(row.get("status"));
        Map<String, Object> member = new LinkedHashMap<>();
        member.put("identityId", String.valueOf(row.get("id")));
        member.put("email", row.get("email"));
        member.put("displayName", row.get("display_name"));
        member.put("role", row.get("role"));
        member.put("lifecycleStatus", STATUS_SUSPENDED.equals(status) ? STATUS_SUSPENDED : STATUS_ACTIVE);
        return member;
    }

    private String deriveLifecycleStatus(Map<String, Object> row) {
        String identityStatus = String.valueOf(row.get("identity_status"));
        if (STATUS_SUSPENDED.equals(identityStatus)) return STATUS_SUSPENDED;
        if (STATUS_REVOKED.equals(identityStatus)) return STATUS_REVOKED;
        if (row.get("accepted_at") != null || STATUS_ACTIVE.equals(identityStatus)) return STATUS_ACTIVE;

        if (row.get("revoked_at") != null) return STATUS_REVOKED;

        LocalDateTime expiresAt = parseDateTime(row.get("expires_at"));
        if (expiresAt != null && expiresAt.isBefore(LocalDateTime.now())) {
            return STATUS_EXPIRED;
        }

        if (STATUS_INVITED.equals(identityStatus)) return STATUS_INVITED;
        return STATUS_ACTIVE;
    }

    private void requireEnabled() {
        if (!isEnabled()) throw new IllegalStateException("invite_tokens table not found — run invite_tokens_migration.sql");
    }

    private String generateRawToken() {
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }

    private LocalDateTime parseDateTime(Object value) {
        if (value == null) return null;
        if (value instanceof LocalDateTime ldt) return ldt;
        if (value instanceof java.sql.Timestamp ts) return ts.toLocalDateTime();
        if (value instanceof java.util.Date d) {
            return LocalDateTime.ofInstant(d.toInstant(), java.time.ZoneId.systemDefault());
        }
        try {
            return LocalDateTime.parse(String.valueOf(value));
        } catch (Exception ignored) {
            return null;
        }
    }

    private String normalizeRole(String role) {
        if (role == null) return Role.VIEWER;
        return switch (role.trim().toUpperCase()) {
            case "OWNER", "ADMIN", "ANALYST", "VIEWER" -> role.trim().toUpperCase();
            default -> Role.VIEWER;
        };
    }
}
