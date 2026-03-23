const BASE_URL = 'http://localhost:5000';

export async function buildFullCatalogue({ schema, tenantId, clientId }) {
  const response = await fetch(`${BASE_URL}/api/catalogue/build-full`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ schema, tenantId, clientId }),
  });
  const payload = await response.json().catch(() => ({}));
  if (!response.ok) throw new Error(payload.error || 'Failed to build catalogue');
  return payload;
}

export async function saveCatalogue({ clientId, catalogueResult }) {
  const response = await fetch(`${BASE_URL}/api/catalogue/save?clientId=${encodeURIComponent(clientId)}`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(catalogueResult),
  });
  const payload = await response.json().catch(() => ({}));
  if (!response.ok) throw new Error(payload.error || 'Failed to save catalogue');
  return payload;
}

export async function listCataloguesForClient(clientId) {
  const response = await fetch(
    `${BASE_URL}/api/catalogue/list?clientId=${encodeURIComponent(clientId)}`
  );
  const payload = await response.json().catch(() => []);
  if (!response.ok) throw new Error(payload.error || 'Failed to load catalogues');
  return Array.isArray(payload) ? payload : [];
}

export async function listCataloguesForTenantContext({ tenantId, tenantSchema }) {
  const params = new URLSearchParams();
  if (tenantId) params.set('tenantId', tenantId);
  if (tenantSchema) params.set('tenantSchema', tenantSchema);
  const response = await fetch(`${BASE_URL}/api/catalogue/list?${params.toString()}`);
  const payload = await response.json().catch(() => []);
  if (!response.ok) throw new Error(payload.error || 'Failed to load catalogues');
  return Array.isArray(payload) ? payload : [];
}

export async function getCataloguePreview(id, { clientId, tenantId, tenantSchema } = {}) {
  const params = new URLSearchParams();
  if (clientId) params.set('clientId', clientId);
  if (tenantId) params.set('tenantId', tenantId);
  if (tenantSchema) params.set('tenantSchema', tenantSchema);
  const response = await fetch(`${BASE_URL}/api/catalogue/${encodeURIComponent(id)}?${params.toString()}`);
  const payload = await response.json().catch(() => ({}));
  if (!response.ok) throw new Error(payload.error || 'Failed to load catalogue preview');
  return payload;
}

export async function approveCatalogue(id, clientId) {
  const response = await fetch(
    `${BASE_URL}/api/catalogue/${encodeURIComponent(id)}/approve?clientId=${encodeURIComponent(clientId)}`,
    { method: 'POST' }
  );
  const payload = await response.json().catch(() => ({}));
  if (!response.ok) throw new Error(payload.error || 'Failed to approve catalogue');
  return payload;
}

export async function rejectCatalogue(id, clientId) {
  const response = await fetch(
    `${BASE_URL}/api/catalogue/${encodeURIComponent(id)}/reject?clientId=${encodeURIComponent(clientId)}`,
    { method: 'POST' }
  );
  const payload = await response.json().catch(() => ({}));
  if (!response.ok) throw new Error(payload.error || 'Failed to reject catalogue');
  return payload;
}

export async function updateCatalogue(id, { tenantId, tenantSchema, clientId, tables }) {
  const params = new URLSearchParams();
  if (tenantId) params.set('tenantId', tenantId);
  if (tenantSchema) params.set('tenantSchema', tenantSchema);
  if (clientId) params.set('clientId', clientId);
  const response = await fetch(`${BASE_URL}/api/catalogue/${encodeURIComponent(id)}?${params.toString()}`, {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ tables }),
  });
  const payload = await response.json().catch(() => ({}));
  if (!response.ok) throw new Error(payload.error || 'Failed to update catalogue');
  return payload;
}
