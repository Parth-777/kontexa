import { getAuthHeaders } from './session';

const BASE_URL = 'http://localhost:5000';

/** Unified workspace login — Workspace ID + password only */
export async function workspaceLogin({ workspaceId, password, deviceLabel }) {
  const response = await fetch(`${BASE_URL}/api/auth/login`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ workspaceId, password, deviceLabel }),
  });
  const payload = await response.json().catch(() => ({}));
  if (!response.ok) throw new Error(payload.error || 'Login failed');
  return payload;
}

/**
 * MCP login handoff. Called right after a successful login when the sign-in page
 * is in MCP mode (`/signin?mcp=1`). Exchanges the just-issued access token for a
 * single-use loopback redirect URL that hands a code back to the local MCP.
 */
export async function mcpComplete({ redirectUri, state, accessToken }) {
  const response = await fetch(`${BASE_URL}/api/auth/mcp/complete`, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      ...(accessToken ? { Authorization: `Bearer ${accessToken}` } : {}),
    },
    body: JSON.stringify({ redirectUri, state }),
  });
  const payload = await response.json().catch(() => ({}));
  if (!response.ok) throw new Error(payload.error || 'MCP login handoff failed');
  return payload; // { redirectUri }
}

export async function refreshSession(refreshToken) {
  const response = await fetch(`${BASE_URL}/api/auth/refresh`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ refreshToken }),
  });
  const payload = await response.json().catch(() => ({}));
  if (!response.ok) throw new Error(payload.error || 'Session refresh failed');
  return payload;
}

export async function logoutSession(accessToken) {
  await fetch(`${BASE_URL}/api/auth/logout`, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      ...(accessToken ? { Authorization: `Bearer ${accessToken}` } : {}),
    },
    body: JSON.stringify({ accessToken }),
  });
}

export async function inviteWorkspaceMember({ email, role }) {
  const response = await fetch(`${BASE_URL}/api/auth/invites`, {
    method: 'POST',
    headers: getAuthHeaders(),
    body: JSON.stringify({ email, role }),
  });
  const payload = await response.json().catch(() => ({}));
  if (!response.ok) throw new Error(payload.error || 'Failed to send invite');
  return payload;
}

export async function validateInviteToken(token) {
  const response = await fetch(`${BASE_URL}/api/auth/invites/validate?token=${encodeURIComponent(token)}`);
  const payload = await response.json().catch(() => ({}));
  if (!response.ok) throw new Error(payload.error || 'Invalid invite');
  return payload;
}

export async function fetchWorkspaceMembers() {
  const response = await fetch(`${BASE_URL}/api/workspace/members`, {
    headers: getAuthHeaders(),
  });
  const payload = await response.json().catch(() => ({}));
  if (!response.ok) throw new Error(payload.error || 'Failed to load team members');
  return payload;
}

export async function resendWorkspaceInvite(inviteId) {
  const response = await fetch(`${BASE_URL}/api/auth/invites/${encodeURIComponent(inviteId)}/resend`, {
    method: 'POST',
    headers: getAuthHeaders(),
  });
  const payload = await response.json().catch(() => ({}));
  if (!response.ok) throw new Error(payload.error || 'Failed to resend invite');
  return payload;
}

export async function revokeWorkspaceInvite(inviteId) {
  const response = await fetch(`${BASE_URL}/api/auth/invites/${encodeURIComponent(inviteId)}/revoke`, {
    method: 'POST',
    headers: getAuthHeaders(),
  });
  const payload = await response.json().catch(() => ({}));
  if (!response.ok) throw new Error(payload.error || 'Failed to revoke invite');
  return payload;
}

export async function updateWorkspaceMemberRole({ identityId, role }) {
  const response = await fetch(`${BASE_URL}/api/workspace/members/${encodeURIComponent(identityId)}/role`, {
    method: 'PUT',
    headers: getAuthHeaders(),
    body: JSON.stringify({ role }),
  });
  const payload = await response.json().catch(() => ({}));
  if (!response.ok) throw new Error(payload.error || 'Failed to update role');
  return payload;
}

export async function updateWorkspaceMemberStatus({ identityId, active }) {
  const response = await fetch(`${BASE_URL}/api/workspace/members/${encodeURIComponent(identityId)}/status`, {
    method: 'PUT',
    headers: getAuthHeaders(),
    body: JSON.stringify({ active }),
  });
  const payload = await response.json().catch(() => ({}));
  if (!response.ok) throw new Error(payload.error || 'Failed to update status');
  return payload;
}

export async function acceptInvite({ token, password, displayName }) {
  const response = await fetch(`${BASE_URL}/api/auth/invites/accept`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ token, password, displayName }),
  });
  const payload = await response.json().catch(() => ({}));
  if (!response.ok) throw new Error(payload.error || 'Failed to accept invite');
  return payload;
}

export async function requestPasswordReset(email) {
  const response = await fetch(`${BASE_URL}/api/auth/password-reset/request`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ email }),
  });
  const payload = await response.json().catch(() => ({}));
  if (!response.ok) throw new Error(payload.error || 'Failed to request reset');
  return payload;
}

export async function confirmPasswordReset({ token, password }) {
  const response = await fetch(`${BASE_URL}/api/auth/password-reset/confirm`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ token, password }),
  });
  const payload = await response.json().catch(() => ({}));
  if (!response.ok) throw new Error(payload.error || 'Failed to reset password');
  return payload;
}

// ── Legacy endpoints (backward compat during migration) ─────────────────────

export async function tenantLogin(userId, password) {
  const response = await fetch(`${BASE_URL}/api/auth/tenant/login`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ userId, password }),
  });
  const payload = await response.json().catch(() => ({}));
  if (!response.ok) throw new Error(payload.error || 'Login failed');
  return payload;
}

export async function userLogin(userId, password) {
  const response = await fetch(`${BASE_URL}/api/auth/user/login`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ userId, password }),
  });
  const payload = await response.json().catch(() => ({}));
  if (!response.ok) throw new Error(payload.error || 'Login failed');
  return payload;
}

export async function fetchTenantUsers(tenantId) {
  const response = await fetch(`${BASE_URL}/api/tenant/users?tenantId=${encodeURIComponent(tenantId)}`);
  const payload = await response.json().catch(() => ({}));
  if (!response.ok) throw new Error(payload.error || 'Failed to load users');
  return payload;
}

export async function updateTenantUserRole({ tenantId, userId, position }) {
  const response = await fetch(`${BASE_URL}/api/tenant/users/${encodeURIComponent(userId)}/role`, {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ tenantId, position }),
  });
  const payload = await response.json().catch(() => ({}));
  if (!response.ok) throw new Error(payload.error || 'Failed to update role');
  return payload;
}

export async function updateTenantUserStatus({ tenantId, userId, active }) {
  const response = await fetch(`${BASE_URL}/api/tenant/users/${encodeURIComponent(userId)}/status`, {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ tenantId, active }),
  });
  const payload = await response.json().catch(() => ({}));
  if (!response.ok) throw new Error(payload.error || 'Failed to update status');
  return payload;
}

export async function deleteTenantUser({ tenantId, userId }) {
  const response = await fetch(
    `${BASE_URL}/api/tenant/users/${encodeURIComponent(userId)}?tenantId=${encodeURIComponent(tenantId)}`,
    { method: 'DELETE' }
  );
  const payload = await response.json().catch(() => ({}));
  if (!response.ok) throw new Error(payload.error || 'Failed to delete user');
  return payload;
}

export async function testTenantBigQueryConnection({ projectId, serviceAccountJson, location, dataset }) {
  const response = await fetch(`${BASE_URL}/api/auth/tenant/bigquery/test-connection`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ projectId, serviceAccountJson, location, dataset }),
  });
  const payload = await response.json().catch(() => ({}));
  if (!response.ok) throw new Error(payload.error || 'Failed to test BigQuery connection');
  return payload;
}

export async function connectTenantBigQuery({ tenantId, projectId, serviceAccountJson, location, dataset }) {
  const response = await fetch(`${BASE_URL}/api/auth/tenant/bigquery/connect`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ tenantId, projectId, serviceAccountJson, location, dataset }),
  });
  const payload = await response.json().catch(() => ({}));
  if (!response.ok) throw new Error(payload.error || 'Failed to connect BigQuery');
  return payload;
}

export async function fetchTenantBigQueryConfig(tenantId) {
  const response = await fetch(
    `${BASE_URL}/api/auth/tenant/bigquery/config?tenantId=${encodeURIComponent(tenantId)}`
  );
  const payload = await response.json().catch(() => ({}));
  if (!response.ok) throw new Error(payload.error || 'Failed to load BigQuery config');
  return payload;
}

export async function fetchTenantBigQueryTables(tenantId) {
  const response = await fetch(
    `${BASE_URL}/api/auth/tenant/bigquery/tables?tenantId=${encodeURIComponent(tenantId)}`
  );
  const payload = await response.json().catch(() => ({}));
  if (!response.ok) throw new Error(payload.error || 'Failed to load BigQuery tables');
  return payload;
}
