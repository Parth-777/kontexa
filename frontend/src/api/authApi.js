const BASE_URL = 'http://localhost:5000';

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

export async function createTenantUser({ tenantId, userId, password, position, active }) {
  const response = await fetch(`${BASE_URL}/api/tenant/users`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ tenantId, userId, password, position, active }),
  });
  const payload = await response.json().catch(() => ({}));
  if (!response.ok) throw new Error(payload.error || 'Failed to create user');
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

export async function fetchTenantCloudLink(tenantId) {
  const response = await fetch(
    `${BASE_URL}/api/auth/tenant/cloud-link?tenantId=${encodeURIComponent(tenantId)}`
  );
  const payload = await response.json().catch(() => ({}));
  if (!response.ok) throw new Error(payload.error || 'Failed to load cloud database link');
  return payload;
}

export async function updateTenantCloudLink({ tenantId, cloudDbLink }) {
  const response = await fetch(`${BASE_URL}/api/auth/tenant/cloud-link`, {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ tenantId, cloudDbLink }),
  });
  const payload = await response.json().catch(() => ({}));
  if (!response.ok) throw new Error(payload.error || 'Failed to update cloud database link');
  return payload;
}
