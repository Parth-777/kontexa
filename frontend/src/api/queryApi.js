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

export async function fetchTablesForSchema(schema) {
  const response = await fetch(`${BASE_URL}/api/user/tables?schema=${encodeURIComponent(schema)}`);
  const payload = await response.json().catch(() => ({}));
  if (!response.ok) throw new Error(payload.error || 'Failed to load tables');
  return payload;
}