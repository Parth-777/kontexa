const SESSION_KEY = 'kontexaSession';

export function saveSession(session) {
  window.sessionStorage.setItem(SESSION_KEY, JSON.stringify(session));
}

export function getSession() {
  const raw = window.sessionStorage.getItem(SESSION_KEY);
  if (!raw) return null;
  try {
    return JSON.parse(raw);
  } catch {
    return null;
  }
}

export function clearSession() {
  window.sessionStorage.removeItem(SESSION_KEY);
  window.sessionStorage.removeItem('kontexaTenantSession');
  window.sessionStorage.removeItem('kontexaUserSession');
}

export function isAuthenticated() {
  return Boolean(getSession());
}

export function getAuthHeaders() {
  const session = getSession();
  const headers = { 'Content-Type': 'application/json' };
  if (session?.accessToken) {
    headers.Authorization = `Bearer ${session.accessToken}`;
  }
  return headers;
}

export function isAdminRole(role) {
  return role === 'OWNER' || role === 'ADMIN';
}

/** True when an API helper failed due to missing/invalid session (401) or RBAC (403). */
export function isAuthFailureMessage(message) {
  const msg = String(message || '').toLowerCase();
  return msg.includes('authentication required')
    || msg.includes('invalid or expired session')
    || msg.includes('insufficient permissions')
    || msg.includes('admin access required')
    || msg.includes('query access required')
    || msg.includes('sign out and sign in');
}

/**
 * Parse JSON body and throw with clear 401/403 messages so callers do not
 * collapse auth failures into empty UI states.
 */
export async function readJsonOrThrow(response, fallbackError) {
  const payload = await response.json().catch(() => ({}));
  if (response.ok) return payload;
  if (response.status === 401) {
    throw new Error(
      (payload && payload.error) || 'Authentication required. Sign out and sign in again.'
    );
  }
  if (response.status === 403) {
    throw new Error(
      (payload && payload.error) || 'Insufficient permissions for this action.'
    );
  }
  const apiError = payload && (payload.error || payload.message || payload.detail);
  const genericStatus = typeof apiError === 'string'
    && /^(bad request|internal server error|not found|unauthorized|forbidden)$/i.test(apiError.trim());
  const detailed = payload && (payload.message || payload.detail);
  throw new Error(
    (!genericStatus && apiError) || detailed || fallbackError || `Request failed (${response.status})`
  );
}
