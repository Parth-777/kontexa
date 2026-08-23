import {
  connectTenantBigQuery,
  connectTenantPostgres,
  connectTenantSnowflake,
  fetchTenantBigQueryConfig,
  fetchTenantPostgresConfig,
  fetchTenantSnowflakeConfig,
  testTenantBigQueryConnection,
  testTenantPostgresConnection,
  testTenantSnowflakeConnection,
} from './authApi';
import {
  approveCatalogue,
  buildFullCatalogue,
  listCataloguesForTenantContext,
  rejectCatalogue,
  saveCatalogue,
  updateCatalogue,
} from './catalogueApi';
import { askQuestion, runAgentDashboard } from './queryApi';
import { readJsonOrThrow, saveSession, clearSession } from './session';

describe('frontend auth propagation for protected APIs', () => {
  const token = 'test-access-token-abc';

  beforeEach(() => {
    clearSession();
    saveSession({
      accessToken: token,
      tenantId: 'acme',
      role: 'ADMIN',
      workspaceSlug: 'acme',
    });
    global.fetch = jest.fn().mockResolvedValue({
      ok: true,
      status: 200,
      json: async () => ({}),
    });
  });

  afterEach(() => {
    clearSession();
    jest.resetAllMocks();
  });

  function expectBearerAndJson(callIndex = 0) {
    expect(global.fetch).toHaveBeenCalled();
    const [, options] = global.fetch.mock.calls[callIndex];
    expect(options.headers.Authorization).toBe(`Bearer ${token}`);
    expect(options.headers['Content-Type']).toBe('application/json');
    return options;
  }

  test('BigQuery test-connection sends Authorization Bearer token', async () => {
    await testTenantBigQueryConnection({
      projectId: 'p',
      serviceAccountJson: '{}',
      location: 'US',
      dataset: 'd',
    });
    const options = expectBearerAndJson();
    expect(options.method).toBe('POST');
    expect(options.body).toContain('projectId');
    expect(global.fetch.mock.calls[0][0]).toContain('/api/auth/tenant/bigquery/test-connection');
  });

  test('BigQuery connect/save sends Authorization Bearer token', async () => {
    await connectTenantBigQuery({
      tenantId: 'acme',
      projectId: 'p',
      serviceAccountJson: '{}',
      location: 'US',
      dataset: 'd',
    });
    const options = expectBearerAndJson();
    expect(options.method).toBe('POST');
    expect(options.body).toContain('tenantId');
    expect(global.fetch.mock.calls[0][0]).toContain('/api/auth/tenant/bigquery/connect');
  });

  test('BigQuery config fetch sends Authorization Bearer token', async () => {
    await fetchTenantBigQueryConfig('acme');
    expectBearerAndJson();
    expect(global.fetch.mock.calls[0][0]).toContain('/api/auth/tenant/bigquery/config');
  });

  test('Snowflake test-connection sends Authorization Bearer token', async () => {
    await testTenantSnowflakeConnection({
      account: 'xy12345',
      warehouse: 'WH',
      database: 'DB',
      schema: 'PUBLIC',
      username: 'u',
      password: 'p',
    });
    const options = expectBearerAndJson();
    expect(options.method).toBe('POST');
    expect(options.body).toContain('account');
    expect(global.fetch.mock.calls[0][0]).toContain('/api/auth/tenant/snowflake/test-connection');
  });

  test('Snowflake connect/save sends Authorization Bearer token', async () => {
    await connectTenantSnowflake({
      tenantId: 'acme',
      account: 'xy12345',
      warehouse: 'WH',
      database: 'DB',
      schema: 'PUBLIC',
      username: 'u',
      password: 'p',
    });
    const options = expectBearerAndJson();
    expect(options.method).toBe('POST');
    expect(options.body).toContain('tenantId');
    expect(global.fetch.mock.calls[0][0]).toContain('/api/auth/tenant/snowflake/connect');
  });

  test('Snowflake config fetch sends Authorization Bearer token', async () => {
    await fetchTenantSnowflakeConfig('acme');
    expectBearerAndJson();
    expect(global.fetch.mock.calls[0][0]).toContain('/api/auth/tenant/snowflake/config');
  });

  test('PostgreSQL test-connection sends Authorization Bearer token', async () => {
    await testTenantPostgresConnection({
      host: 'db.example.com',
      port: 5432,
      database: 'analytics',
      schema: 'public',
      username: 'u',
      password: 'p',
      ssl: true,
    });
    const options = expectBearerAndJson();
    expect(options.method).toBe('POST');
    expect(options.body).toContain('host');
    expect(global.fetch.mock.calls[0][0]).toContain('/api/auth/tenant/postgres/test-connection');
  });

  test('PostgreSQL connect/save sends Authorization Bearer token', async () => {
    await connectTenantPostgres({
      tenantId: 'acme',
      host: 'db.example.com',
      port: 5432,
      database: 'analytics',
      schema: 'public',
      username: 'u',
      password: 'p',
      ssl: false,
    });
    const options = expectBearerAndJson();
    expect(options.method).toBe('POST');
    expect(options.body).toContain('tenantId');
    expect(global.fetch.mock.calls[0][0]).toContain('/api/auth/tenant/postgres/connect');
  });

  test('PostgreSQL config fetch sends Authorization Bearer token', async () => {
    await fetchTenantPostgresConfig('acme');
    expectBearerAndJson();
    expect(global.fetch.mock.calls[0][0]).toContain('/api/auth/tenant/postgres/config');
  });

  test('Catalogue list sends Authorization Bearer token', async () => {
    global.fetch.mockResolvedValueOnce({
      ok: true,
      status: 200,
      json: async () => [],
    });
    await listCataloguesForTenantContext({ tenantId: 'acme', tenantSchema: 'acme' });
    expectBearerAndJson();
    expect(global.fetch.mock.calls[0][0]).toContain('/api/catalogue/list');
  });

  test('Catalogue protected mutations send Authorization Bearer token', async () => {
    await buildFullCatalogue({ schema: 'acme', tenantId: 'acme', clientId: 'acme' });
    expectBearerAndJson(0);
    await saveCatalogue({ clientId: 'acme', catalogueResult: { tables: [] } });
    expectBearerAndJson(1);
    await approveCatalogue(1, 'acme');
    expectBearerAndJson(2);
    await rejectCatalogue(1, 'acme');
    expectBearerAndJson(3);
    await updateCatalogue(1, { tenantId: 'acme', tables: [] });
    expectBearerAndJson(4);
  });

  test('Existing Content-Type/body behavior remains unchanged for BigQuery test', async () => {
    await testTenantBigQueryConnection({
      projectId: 'proj',
      serviceAccountJson: '{"type":"service_account"}',
      location: 'EU',
      dataset: 'analytics',
    });
    const options = expectBearerAndJson();
    const body = JSON.parse(options.body);
    expect(body).toEqual({
      projectId: 'proj',
      serviceAccountJson: '{"type":"service_account"}',
      location: 'EU',
      dataset: 'analytics',
    });
  });

  test('query/agent protected callers send Authorization Bearer token', async () => {
    await askQuestion('acme', 'why?');
    const askOpts = expectBearerAndJson(0);
    expect(askOpts.headers['X-Client-Id']).toBe('acme');
    await runAgentDashboard('acme');
    expectBearerAndJson(1);
  });

  test('readJsonOrThrow prefers detailed message over generic Bad Request', async () => {
    await expect(
      readJsonOrThrow(
        {
          ok: false,
          status: 400,
          json: async () => ({
            error: 'Bad Request',
            message: 'Warehouse discovery failed: Arrow chunk',
          }),
        },
        'Failed to build catalogue'
      )
    ).rejects.toThrow('Warehouse discovery failed: Arrow chunk');
  });

  test('readJsonOrThrow maps 401 to authentication error', async () => {
    await expect(
      readJsonOrThrow(
        { ok: false, status: 401, json: async () => ({ error: 'Authentication required' }) },
        'fallback'
      )
    ).rejects.toThrow('Authentication required');
  });

  test('readJsonOrThrow maps 403 to insufficient permissions', async () => {
    await expect(
      readJsonOrThrow(
        { ok: false, status: 403, json: async () => ({ error: 'Admin access required' }) },
        'fallback'
      )
    ).rejects.toThrow('Admin access required');
  });

  test('missing accessToken still builds Content-Type but no Authorization', async () => {
    clearSession();
    saveSession({ tenantId: 'acme', role: 'ADMIN' });
    await fetchTenantBigQueryConfig('acme');
    const [, options] = global.fetch.mock.calls[0];
    expect(options.headers['Content-Type']).toBe('application/json');
    expect(options.headers.Authorization).toBeUndefined();
  });
});
