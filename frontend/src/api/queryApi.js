const BASE_URL = 'http://localhost:5000';

export async function askQuestion(clientId, question) {
  const response = await fetch(`${BASE_URL}/api/query/ask`, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      'X-Client-Id': clientId,
    },
    body: JSON.stringify({ question }),
  });
  const payload = await response.json().catch(() => ({}));
  if (!response.ok) throw new Error(payload.error || 'Something went wrong');
  return payload;
}

export async function runAgentDashboard(clientId) {
  const response = await fetch(`${BASE_URL}/api/agent/dashboard`, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      'X-Client-Id': clientId,
    },
    body: JSON.stringify({}),
  });
  const payload = await response.json().catch(() => ({}));
  if (!response.ok) throw new Error(payload.error || 'Agent analysis failed');
  return payload;
}

export async function fetchTablesForSchema(schema, tenantId) {
  const params = new URLSearchParams({ schema });
  if (tenantId) params.append('tenantId', tenantId);
  const response = await fetch(`${BASE_URL}/api/user/tables?${params.toString()}`);
  const payload = await response.json().catch(() => ({}));
  if (!response.ok) throw new Error(payload.error || 'Failed to load tables');
  return payload;
}