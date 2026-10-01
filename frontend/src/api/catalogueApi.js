import { getAuthHeaders, readJsonOrThrow } from './session';

const BASE_URL = process.env.REACT_APP_API_BASE_URL;

export async function buildFullCatalogue({ schema, tenantId, clientId }) {
  const response = await fetch(`${BASE_URL}/api/catalogue/build-full`, {
    method: 'POST',
    headers: getAuthHeaders(),
    body: JSON.stringify({ schema, tenantId, clientId }),
  });
  return readJsonOrThrow(response, 'Failed to build catalogue');
}

export async function saveCatalogue({ clientId, catalogueResult }) {
  const response = await fetch(`${BASE_URL}/api/catalogue/save?clientId=${encodeURIComponent(clientId)}`, {
    method: 'POST',
    headers: getAuthHeaders(),
    body: JSON.stringify(catalogueResult),
  });
  return readJsonOrThrow(response, 'Failed to save catalogue');
}

export async function listCataloguesForClient(clientId) {
  const response = await fetch(
    `${BASE_URL}/api/catalogue/list?clientId=${encodeURIComponent(clientId)}`,
    { headers: getAuthHeaders() }
  );
  const payload = await readJsonOrThrow(response, 'Failed to load catalogues');
  return Array.isArray(payload) ? payload : [];
}

export async function listCataloguesForTenantContext({ tenantId, tenantSchema }) {
  const params = new URLSearchParams();
  if (tenantId) params.set('tenantId', tenantId);
  if (tenantSchema) params.set('tenantSchema', tenantSchema);
  const response = await fetch(`${BASE_URL}/api/catalogue/list?${params.toString()}`, {
    headers: getAuthHeaders(),
  });
  const payload = await readJsonOrThrow(response, 'Failed to load catalogues');
  return Array.isArray(payload) ? payload : [];
}

export async function getCataloguePreview(id, { clientId, tenantId, tenantSchema } = {}) {
  const params = new URLSearchParams();
  if (clientId) params.set('clientId', clientId);
  if (tenantId) params.set('tenantId', tenantId);
  if (tenantSchema) params.set('tenantSchema', tenantSchema);
  const response = await fetch(
    `${BASE_URL}/api/catalogue/${encodeURIComponent(id)}?${params.toString()}`,
    { headers: getAuthHeaders() }
  );
  return readJsonOrThrow(response, 'Failed to load catalogue preview');
}

export async function approveCatalogue(id, clientId) {
  const response = await fetch(
    `${BASE_URL}/api/catalogue/${encodeURIComponent(id)}/approve?clientId=${encodeURIComponent(clientId)}`,
    { method: 'POST', headers: getAuthHeaders() }
  );
  return readJsonOrThrow(response, 'Failed to approve catalogue');
}

export async function rejectCatalogue(id, clientId) {
  const response = await fetch(
    `${BASE_URL}/api/catalogue/${encodeURIComponent(id)}/reject?clientId=${encodeURIComponent(clientId)}`,
    { method: 'POST', headers: getAuthHeaders() }
  );
  return readJsonOrThrow(response, 'Failed to reject catalogue');
}

export async function updateCatalogue(id, { tenantId, tenantSchema, clientId, tables }) {
  const params = new URLSearchParams();
  if (tenantId) params.set('tenantId', tenantId);
  if (tenantSchema) params.set('tenantSchema', tenantSchema);
  if (clientId) params.set('clientId', clientId);
  const response = await fetch(`${BASE_URL}/api/catalogue/${encodeURIComponent(id)}?${params.toString()}`, {
    method: 'PUT',
    headers: getAuthHeaders(),
    body: JSON.stringify({ tables }),
  });
  return readJsonOrThrow(response, 'Failed to update catalogue');
}
