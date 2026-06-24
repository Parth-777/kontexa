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
