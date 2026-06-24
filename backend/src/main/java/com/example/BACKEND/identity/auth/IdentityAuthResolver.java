package com.example.BACKEND.identity.auth;

import com.example.BACKEND.identity.IdentityRepository;
import com.example.BACKEND.identity.WorkspaceIdentityDomain.*;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Canonical authentication against identities + workspace_memberships.
 * No legacy table access — that is handled by {@link LegacyAuthBridge}.
 */
@Component
public class IdentityAuthResolver {

    private final IdentityRepository repo;
    private final PasswordService passwords;

    public IdentityAuthResolver(IdentityRepository repo, PasswordService passwords) {
        this.repo = repo;
        this.passwords = passwords;
    }

    public Optional<AuthResult> resolve(String emailOrUserId, String password, String workspaceSlug) {
        if (!repo.identityTablesExist()) return Optional.empty();
        if (emailOrUserId == null || password == null) return Optional.empty();

        String identifier = emailOrUserId.trim();

        // 1. Legacy user id from migration (admin tenant id or app user id)
        Optional<ResolvedLogin> login = repo.findLoginByLegacyUserId(identifier);

        // 2. Workspace-scoped lookup (admin workspace slug = tenant id)
        if (login.isEmpty() && workspaceSlug != null && !workspaceSlug.isBlank()) {
            login = resolveScoped(workspaceSlug.trim(), identifier);
        }

        // 3. Email or synthetic email from identifier
        if (login.isEmpty()) {
            login = repo.findFirstMembershipLogin(identifier);
        }
        if (login.isEmpty() && !identifier.contains("@")) {
            login = repo.findFirstMembershipLogin(identifier + "@kontexa.workspace");
        }

        if (login.isEmpty()) return Optional.empty();

        ResolvedLogin resolved = login.get();
        Identity identity = resolved.identity();

        if (!"ACTIVE".equals(identity.status())) return Optional.empty();
        if (!passwords.matches(password, identity.passwordHash())) return Optional.empty();

        repo.updateLastLogin(identity.id());

        String connectorJson = repo.findConnectorCredentials(resolved.workspaceId()).orElse(null);

        return Optional.of(new AuthResult(
                identity.id(),
                resolved.workspaceId(),
                resolved.workspaceSlug(),
                identity.email(),
                identity.displayName() != null ? identity.displayName() : identity.email(),
                resolved.role(),
                connectorJson,
                AuthSource.IDENTITY_V2
        ));
    }

    public List<Map<String, Object>> listWorkspacesForEmail(String email) {
        return repo.listWorkspacesForEmail(email);
    }

    public Optional<AuthResult> resolveForWorkspace(UUID identityId, UUID workspaceId) {
        Optional<Identity> identityOpt = repo.findIdentityById(identityId);
        if (identityOpt.isEmpty()) return Optional.empty();

        Identity identity = identityOpt.get();
        Optional<Workspace> ws = repo.findWorkspaceById(workspaceId);
        if (ws.isEmpty()) return Optional.empty();

        String role = repo.findRoleForIdentityInWorkspace(workspaceId, identityId).orElse(Role.VIEWER);
        String connectorJson = repo.findConnectorCredentials(workspaceId).orElse(null);

        return Optional.of(new AuthResult(
                identity.id(),
                workspaceId,
                ws.get().slug(),
                identity.email(),
                identity.displayName() != null ? identity.displayName() : identity.email(),
                role,
                connectorJson,
                AuthSource.IDENTITY_V2
        ));
    }

    private Optional<ResolvedLogin> resolveScoped(String workspaceSlug, String email) {
        Optional<UUID> wsId = repo.findWorkspaceIdBySlug(workspaceSlug);
        if (wsId.isEmpty()) return Optional.empty();

        // Membership-first: identity may exist via membership even if workspace_id column differs
        Optional<ResolvedLogin> viaMembership = repo.findMembershipLogin(wsId.get(), email);
        if (viaMembership.isPresent()) return viaMembership;

        return repo.findIdentityByWorkspaceAndEmail(wsId.get(), email)
                .map(i -> new ResolvedLogin(i, wsId.get(), workspaceSlug,
                        repo.findRoleForIdentityInWorkspace(wsId.get(), i.id()).orElse(Role.VIEWER)));
    }

    private Optional<ResolvedLogin> resolveFirstMembership(String email) {
        return repo.findFirstMembershipLogin(email);
    }

    public record ResolvedLogin(Identity identity, UUID workspaceId, String workspaceSlug, String role) {}
}
