import { getAuthHeaders, readJsonOrThrow } from './session';

const BASE_URL = 'http://localhost:5000';

/** Used by legacy QueryPage — protected list of workspace client keys. */
export async function fetchActiveClients() {
  const response = await fetch(`${BASE_URL}/api/catalogue/list`, {
    headers: getAuthHeaders(),
  });
  const catalogues = await readJsonOrThrow(response, 'Failed to load clients');
  const clients = Array.isArray(catalogues)
    ? [...new Set(catalogues.map((c) => c.clientId).filter(Boolean))]
    : [];
  return { clients };
}

export async function askQuestion(clientId, question, skipClarification = false, history = []) {
  const response = await fetch(`${BASE_URL}/api/query/ask`, {
    method: 'POST',
    headers: {
      ...getAuthHeaders(),
      'X-Client-Id': clientId,
    },
    body: JSON.stringify({ question, skipClarification, history }),
  });
  return readJsonOrThrow(response, 'Something went wrong');
}

export async function getReports(clientId, type) {
  const params = type ? `?type=${type}` : '';
  const response = await fetch(`${BASE_URL}/api/agent/reports${params}`, {
    headers: {
      ...getAuthHeaders(),
      'X-Client-Id': clientId,
    },
  });
  const payload = await readJsonOrThrow(response, 'Failed to load reports');
  return Array.isArray(payload) ? payload : [];
}

export async function generateReport(clientId, type = 'WEEKLY') {
  const response = await fetch(`${BASE_URL}/api/agent/reports/generate`, {
    method: 'POST',
    headers: {
      ...getAuthHeaders(),
      'X-Client-Id': clientId,
    },
    body: JSON.stringify({ type }),
  });
  return readJsonOrThrow(response, 'Report generation failed');
}

export async function runAgentDashboard(clientId) {
  const response = await fetch(`${BASE_URL}/api/agent/dashboard`, {
    method: 'POST',
    headers: {
      ...getAuthHeaders(),
      'X-Client-Id': clientId,
    },
    body: JSON.stringify({}),
  });
  return readJsonOrThrow(response, 'Agent analysis failed');
}

export async function getPersistedInsights(clientId) {
  const response = await fetch(`${BASE_URL}/api/agent/insights`, {
    headers: {
      ...getAuthHeaders(),
      'X-Client-Id': clientId,
    },
  });
  const payload = await readJsonOrThrow(response, 'Failed to load insights');
  return Array.isArray(payload) ? payload : [];
}

export async function getAgentCharts(clientId) {
  const response = await fetch(`${BASE_URL}/api/agent/charts`, {
    headers: {
      ...getAuthHeaders(),
      'X-Client-Id': clientId,
    },
  });
  return readJsonOrThrow(response, 'Failed to load charts');
}

export async function generateChartFromRequest(clientId, request) {
  const response = await fetch(`${BASE_URL}/api/agent/charts/generate`, {
    method: 'POST',
    headers: {
      ...getAuthHeaders(),
      'X-Client-Id': clientId,
    },
    body: JSON.stringify({ request }),
  });
  if (!response.ok && response.status === 404) {
    throw new Error('Chart API not available — restart the backend (port 5000) to load the latest code.');
  }
  return readJsonOrThrow(response, 'Chart generation failed');
}

export async function getFollowUpQuestions(clientId) {
  const response = await fetch(
    `${BASE_URL}/api/agent/insights/followup?clientId=${encodeURIComponent(clientId)}`,
    {
      headers: {
        ...getAuthHeaders(),
        'X-Client-Id': clientId,
      },
    }
  );
  return readJsonOrThrow(response, 'Failed to load follow-up questions');
}

export async function updateInsightStatus(clientId, cardId, status, dismissReason = null) {
  const response = await fetch(`${BASE_URL}/api/agent/insights/${cardId}/status`, {
    method: 'PATCH',
    headers: {
      ...getAuthHeaders(),
      'X-Client-Id': clientId,
    },
    body: JSON.stringify(
      dismissReason ? { status, dismissReason } : { status }
    ),
  });
  return readJsonOrThrow(response, 'Failed to update insight');
}

export async function runDecisionAnalysis(tenantId, question) {
  const response = await fetch(`${BASE_URL}/api/decision/v1/run`, {
    method: 'POST',
    headers: {
      ...getAuthHeaders(),
      'X-Client-Id': tenantId,
    },
    body: JSON.stringify({ question, tenantId }),
  });
  return readJsonOrThrow(response, 'Decision analysis failed');
}

export async function fetchPlannerStatus() {
  const response = await fetch(`${BASE_URL}/api/decision/v1/planner/status`, {
    headers: { ...getAuthHeaders() },
  });
  return readJsonOrThrow(response, 'Failed to load planner status');
}

export async function fetchTablesForSchema(schema, tenantId) {
  const params = new URLSearchParams({ schema });
  if (tenantId) params.append('tenantId', tenantId);
  const response = await fetch(`${BASE_URL}/api/user/tables?${params.toString()}`, {
    headers: getAuthHeaders(),
  });
  return readJsonOrThrow(response, 'Failed to load tables');
}
