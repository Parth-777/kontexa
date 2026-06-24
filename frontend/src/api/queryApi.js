const BASE_URL = 'http://localhost:5000';

export async function askQuestion(clientId, question, skipClarification = false, history = []) {
  const response = await fetch(`${BASE_URL}/api/query/ask`, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      'X-Client-Id': clientId,
    },
    body: JSON.stringify({ question, skipClarification, history }),
  });
  const payload = await response.json().catch(() => ({}));
  if (!response.ok) throw new Error(payload.error || 'Something went wrong');
  return payload;
}

export async function getReports(clientId, type) {
  const params = type ? `?type=${type}` : '';
  const response = await fetch(`${BASE_URL}/api/agent/reports${params}`, {
    headers: { 'X-Client-Id': clientId },
  });
  const payload = await response.json().catch(() => ([]));
  if (!response.ok) throw new Error(payload.error || 'Failed to load reports');
  return payload;
}

export async function generateReport(clientId, type = 'WEEKLY') {
  const response = await fetch(`${BASE_URL}/api/agent/reports/generate`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', 'X-Client-Id': clientId },
    body: JSON.stringify({ type }),
  });
  const payload = await response.json().catch(() => ({}));
  if (!response.ok) throw new Error(payload.error || 'Report generation failed');
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


export async function getPersistedInsights(clientId) {
  const response = await fetch(`${BASE_URL}/api/agent/insights`, {
    headers: { 'X-Client-Id': clientId },
  });
  const payload = await response.json().catch(() => ([]));
  if (!response.ok) throw new Error(payload.error || 'Failed to load insights');
  return payload;
}

export async function getAgentCharts(clientId) {
  const response = await fetch(`${BASE_URL}/api/agent/charts`, {
    headers: { 'X-Client-Id': clientId },
  });
  const payload = await response.json().catch(() => ({}));
  if (!response.ok) throw new Error(payload.error || 'Failed to load charts');
  return payload;
}

export async function generateChartFromRequest(clientId, request) {
  const response = await fetch(`${BASE_URL}/api/agent/charts/generate`, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      'X-Client-Id': clientId,
    },
    body: JSON.stringify({ request }),
  });
  const payload = await response.json().catch(() => ({}));
  if (!response.ok) {
    if (response.status === 404) {
      throw new Error('Chart API not available — restart the backend (port 5000) to load the latest code.');
    }
    throw new Error(payload.error || payload.message || `Chart generation failed (${response.status})`);
  }
  return payload;
}

export async function getFollowUpQuestions(clientId) {
  const response = await fetch(
    `${BASE_URL}/api/agent/insights/followup?clientId=${encodeURIComponent(clientId)}`,
    { headers: { 'X-Client-Id': clientId } }
  );
  const payload = await response.json().catch(() => ({}));
  if (!response.ok) throw new Error(payload.error || 'Failed to load follow-up questions');
  return payload;
}

export async function updateInsightStatus(clientId, cardId, status, dismissReason = null) {
  const response = await fetch(`${BASE_URL}/api/agent/insights/${cardId}/status`, {
    method: 'PATCH',
    headers: {
      'Content-Type': 'application/json',
      'X-Client-Id': clientId,
    },
    body: JSON.stringify(
      dismissReason ? { status, dismissReason } : { status }
    ),
  });
  const payload = await response.json().catch(() => ({}));
  if (!response.ok) throw new Error(payload.error || 'Failed to update insight');
  return payload;
}

export async function runDecisionAnalysis(tenantId, question) {
  const response = await fetch(`${BASE_URL}/api/decision/v1/run`, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      'X-Client-Id': tenantId,
    },
    body: JSON.stringify({ question, tenantId }),
  });
  const payload = await response.json().catch(() => ({}));
  if (!response.ok) throw new Error(payload.error || 'Decision analysis failed');
  return payload;
}

export async function fetchPlannerStatus() {
  const response = await fetch(`${BASE_URL}/api/decision/v1/planner/status`);
  const payload = await response.json().catch(() => ({}));
  if (!response.ok) throw new Error(payload.error || 'Failed to load planner status');
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