import { useEffect, useMemo, useRef, useState } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import {
  connectTenantBigQuery,
  createTenantUser,
  deleteTenantUser,
  fetchTenantBigQueryConfig,
  fetchTenantBigQueryTables,
  fetchTenantUsers,
  testTenantBigQueryConnection,
  updateTenantUserRole,
  updateTenantUserStatus,
} from '../api/authApi';
import {
  approveCatalogue,
  buildFullCatalogue,
  listCataloguesForTenantContext,
  rejectCatalogue,
  saveCatalogue,
} from '../api/catalogueApi';
import './TenantDashboardPage.css';

const roles = ['Admin', 'Manager', 'Analyst', 'Viewer'];

function TenantDashboardPage() {
  const navigate = useNavigate();
  const tenantSession = JSON.parse(window.sessionStorage.getItem('kontexaTenantSession') || '{}');
  const tenantId = tenantSession.tenantId || '';
  const tenantSchema = tenantSession.tenantSchema || '';
  const tenantUserId = tenantSession.userId || '';
  const catalogueClientId = tenantSchema || tenantId;
  const profileMenuRef = useRef(null);

  const [users, setUsers] = useState([]);
  const [activeFilter, setActiveFilter] = useState('all');
  const [searchText, setSearchText] = useState('');
  const [newUser, setNewUser] = useState({ id: '', password: '', role: 'Viewer', isActive: true });
  const [loadingUsers, setLoadingUsers] = useState(true);
  const [savingUser, setSavingUser] = useState(false);
  const [testingBigQuery, setTestingBigQuery] = useState(false);
  const [connectingBigQuery, setConnectingBigQuery] = useState(false);
  const [profileOpen, setProfileOpen] = useState(false);
  const [bigQueryConnectionLink, setBigQueryConnectionLink] = useState('');
  const [bigQueryConnected, setBigQueryConnected] = useState(false);
  const [serviceAccountStored, setServiceAccountStored] = useState(false);
  const [loadingBigQueryTables, setLoadingBigQueryTables] = useState(false);
  const [bigQueryTables, setBigQueryTables] = useState([]);
  const [bigQueryFeedback, setBigQueryFeedback] = useState('');
  const [bigQueryError, setBigQueryError] = useState('');
  const [bigQueryForm, setBigQueryForm] = useState({
    projectId: '',
    location: '',
    dataset: '',
    serviceAccountJson: '',
  });
  const [feedback, setFeedback] = useState('');
  const [errorMessage, setErrorMessage] = useState('');
  const [catalogues, setCatalogues] = useState([]);
  const [loadingCatalogues, setLoadingCatalogues] = useState(false);
  const [catalogueFeedback, setCatalogueFeedback] = useState('');
  const [catalogueError, setCatalogueError] = useState('');
  const [catalogueActionId, setCatalogueActionId] = useState(null);
  const [generatingCatalogue, setGeneratingCatalogue] = useState(false);

  useEffect(() => {
    function onWindowClick(event) {
      if (profileMenuRef.current && !profileMenuRef.current.contains(event.target)) {
        setProfileOpen(false);
      }
    }
    if (profileOpen) window.addEventListener('click', onWindowClick);
    return () => window.removeEventListener('click', onWindowClick);
  }, [profileOpen]);

  useEffect(() => {
    if (!tenantId) return;
    async function loadData() {
      setLoadingUsers(true);
      setLoadingCatalogues(true);
      setErrorMessage('');
      try {
        const [usersPayload, bigQueryPayload, cataloguePayload] = await Promise.all([
          fetchTenantUsers(tenantId),
          fetchTenantBigQueryConfig(tenantId).catch(() => ({ connected: false })),
          tenantId || tenantSchema
            ? listCataloguesForTenantContext({ tenantId, tenantSchema }).catch(() => [])
            : Promise.resolve([]),
        ]);
        setUsers(
          (usersPayload.users || []).map((user) => ({
            id: user.user_id || user.userId,
            role: user.position || 'Viewer',
            isActive: Boolean(user.active),
          }))
        );
        setBigQueryConnected(Boolean(bigQueryPayload.connected));
        setBigQueryConnectionLink(bigQueryPayload.connectionLink || '');
        setServiceAccountStored(Boolean(bigQueryPayload.serviceAccountStored));
      setBigQueryTables([]);
        setBigQueryForm((curr) => ({
          ...curr,
          projectId: bigQueryPayload.projectId || '',
          location: bigQueryPayload.location || '',
          dataset: bigQueryPayload.dataset || '',
          serviceAccountJson: '',
        }));
        setCatalogues(cataloguePayload);
      } catch (error) {
        setErrorMessage(error.message || 'Failed to load tenant dashboard');
      } finally {
        setLoadingUsers(false);
        setLoadingCatalogues(false);
      }
    }
    loadData();
  }, [tenantId, tenantSchema, catalogueClientId]);

  const filteredUsers = useMemo(
    () =>
      users.filter((user) => {
        const filterMatch =
          activeFilter === 'all' ? true : activeFilter === 'active' ? user.isActive : !user.isActive;
        return filterMatch && user.id.toLowerCase().includes(searchText.toLowerCase());
      }),
    [users, activeFilter, searchText]
  );

  const handleAddUser = async (event) => {
    event.preventDefault();
    if (!tenantId) return;
    try {
      setSavingUser(true);
      await createTenantUser({
        tenantId,
        userId: newUser.id.trim(),
        password: newUser.password,
        position: newUser.role,
        active: newUser.isActive,
      });
      const payload = await fetchTenantUsers(tenantId);
      setUsers(
        (payload.users || []).map((user) => ({
          id: user.user_id || user.userId,
          role: user.position || 'Viewer',
          isActive: Boolean(user.active),
        }))
      );
      setNewUser({ id: '', password: '', role: 'Viewer', isActive: true });
      setFeedback('User added successfully.');
    } catch (error) {
      setFeedback(error.message || 'Failed to add user');
    } finally {
      setSavingUser(false);
    }
  };

  const handleRoleChange = async (userId, role) => {
    try {
      await updateTenantUserRole({ tenantId, userId, position: role });
      setUsers((curr) => curr.map((user) => (user.id === userId ? { ...user, role } : user)));
    } catch (error) {
      setFeedback(error.message || 'Failed to update role');
    }
  };

  const handleStatusToggle = async (userId) => {
    const target = users.find((x) => x.id === userId);
    if (!target) return;
    try {
      await updateTenantUserStatus({ tenantId, userId, active: !target.isActive });
      setUsers((curr) =>
        curr.map((user) => (user.id === userId ? { ...user, isActive: !user.isActive } : user))
      );
    } catch (error) {
      setFeedback(error.message || 'Failed to update status');
    }
  };

  const handleRemoveUser = async (userId) => {
    try {
      await deleteTenantUser({ tenantId, userId });
      setUsers((curr) => curr.filter((user) => user.id !== userId));
    } catch (error) {
      setFeedback(error.message || 'Failed to remove user');
    }
  };

  const handleBigQueryField = (key, value) => {
    setBigQueryForm((curr) => ({ ...curr, [key]: value }));
  };

  const handleTestBigQuery = async (event) => {
    event.preventDefault();
    if (!bigQueryForm.projectId.trim() || !bigQueryForm.serviceAccountJson.trim()) {
      setBigQueryError('Project ID and Service Account JSON are required.');
      return;
    }
    setBigQueryFeedback('');
    setBigQueryError('');
    try {
      setTestingBigQuery(true);
      const payload = await testTenantBigQueryConnection({
        projectId: bigQueryForm.projectId.trim(),
        serviceAccountJson: bigQueryForm.serviceAccountJson.trim(),
        location: bigQueryForm.location.trim(),
        dataset: bigQueryForm.dataset.trim(),
      });
      setBigQueryFeedback(`Connection successful. Link: ${payload.connectionLink || ''}`);
    } catch (error) {
      setBigQueryError(error.message || 'BigQuery test failed');
    } finally {
      setTestingBigQuery(false);
    }
  };

  const handleConnectBigQuery = async (event) => {
    event.preventDefault();
    if (!tenantId) return;
    if (!bigQueryForm.projectId.trim() || !bigQueryForm.serviceAccountJson.trim()) {
      setBigQueryError('Project ID and Service Account JSON are required.');
      return;
    }
    setBigQueryFeedback('');
    setBigQueryError('');
    try {
      setConnectingBigQuery(true);
      const payload = await connectTenantBigQuery({
        tenantId,
        projectId: bigQueryForm.projectId.trim(),
        serviceAccountJson: bigQueryForm.serviceAccountJson.trim(),
        location: bigQueryForm.location.trim(),
        dataset: bigQueryForm.dataset.trim(),
      });
      const savedLink = payload.cloudDbLink || '';
      setBigQueryConnectionLink(savedLink);
      setBigQueryConnected(true);
      setServiceAccountStored(true);
      setBigQueryTables([]);
      const updated = { ...tenantSession, cloudDbLink: savedLink };
      window.sessionStorage.setItem('kontexaTenantSession', JSON.stringify(updated));
      setBigQueryForm((curr) => ({ ...curr, serviceAccountJson: '' }));
      setBigQueryFeedback('BigQuery connected and configuration saved successfully.');
    } catch (error) {
      setBigQueryError(error.message || 'Failed to connect BigQuery');
    } finally {
      setConnectingBigQuery(false);
    }
  };

  const handleLoadBigQueryTables = async () => {
    if (!tenantId) return;
    setBigQueryError('');
    setBigQueryFeedback('');
    try {
      setLoadingBigQueryTables(true);
      const payload = await fetchTenantBigQueryTables(tenantId);
      setBigQueryTables(payload.tables || []);
      setBigQueryFeedback(`Loaded ${payload.tableCount || 0} tables from BigQuery.`);
    } catch (error) {
      setBigQueryError(error.message || 'Failed to load BigQuery tables');
    } finally {
      setLoadingBigQueryTables(false);
    }
  };

  const refreshCatalogues = async () => {
    if (!tenantId && !tenantSchema) return;
    setLoadingCatalogues(true);
    setCatalogueError('');
    try {
      const payload = await listCataloguesForTenantContext({ tenantId, tenantSchema });
      setCatalogues(payload);
    } catch (error) {
      setCatalogueError(error.message || 'Failed to refresh catalogues');
    } finally {
      setLoadingCatalogues(false);
    }
  };

  const handleViewCatalogue = (catalogueId) => {
    navigate(`/tenant/catalogue/${catalogueId}`);
  };

  const handleGenerateCatalogue = async () => {
    if (!tenantId) return;
    setGeneratingCatalogue(true);
    setCatalogueError('');
    setCatalogueFeedback('');
    try {
      const built = await buildFullCatalogue({
        schema: tenantSchema || catalogueClientId,
        tenantId,
        clientId: catalogueClientId,
      });
      const savePayload = await saveCatalogue({
        clientId: catalogueClientId,
        catalogueResult: built,
      });
      setCatalogueFeedback(
        `Catalogue generated and saved as draft (#${savePayload.id}). Review and approve it below.`
      );
      await refreshCatalogues();
    } catch (error) {
      setCatalogueError(error.message || 'Failed to generate catalogue');
    } finally {
      setGeneratingCatalogue(false);
    }
  };

  const handleApproveCatalogue = async (catalogueId, catalogueClient) => {
    if (!catalogueClient) return;
    setCatalogueActionId(catalogueId);
    setCatalogueError('');
    try {
      await approveCatalogue(catalogueId, catalogueClient);
      setCatalogueFeedback(`Catalogue #${catalogueId} approved successfully.`);
      await refreshCatalogues();
    } catch (error) {
      setCatalogueError(error.message || 'Failed to approve catalogue');
    } finally {
      setCatalogueActionId(null);
    }
  };

  const handleRejectCatalogue = async (catalogueId, catalogueClient) => {
    if (!catalogueClient) return;
    setCatalogueActionId(catalogueId);
    setCatalogueError('');
    try {
      await rejectCatalogue(catalogueId, catalogueClient);
      setCatalogueFeedback(`Catalogue #${catalogueId} marked as rejected.`);
      await refreshCatalogues();
    } catch (error) {
      setCatalogueError(error.message || 'Failed to reject catalogue');
    } finally {
      setCatalogueActionId(null);
    }
  };

  const handleSignOut = () => {
    window.sessionStorage.removeItem('kontexaTenantSession');
    navigate('/signin');
  };

  return (
    <div className="tenant-page">
      <header className="tenant-header">
        <Link className="tenant-brand" to="/">
          KONTEXA
        </Link>
        <div className="profile-menu" ref={profileMenuRef}>
          <button className="profile-trigger" onClick={() => setProfileOpen((v) => !v)} type="button">
            Profile
          </button>
          {profileOpen ? (
            <div className="profile-popover">
              <p className="profile-title">Tenant Profile</p>
              <div className="profile-item">
                <span>Tenant ID</span>
                <strong>{tenantId || 'Unknown'}</strong>
              </div>
              <div className="profile-item">
                <span>User ID</span>
                <strong>{tenantUserId || 'Unknown'}</strong>
              </div>
              <div className="profile-item">
                <span>Connected Source</span>
                <strong>{bigQueryConnected ? 'BigQuery connected' : 'Not connected'}</strong>
              </div>
              <button className="profile-signout" onClick={handleSignOut} type="button">
                Sign Out
              </button>
            </div>
          ) : null}
        </div>
      </header>

      <main className="tenant-main">
        <section className="tenant-layout">
          <article className="tenant-card">
            <h2>Users</h2>
            <input
              className="tenant-search"
              placeholder="Search users by ID"
              value={searchText}
              onChange={(event) => setSearchText(event.target.value)}
            />
            <div className="tenant-filters">
              {['all', 'active', 'inactive'].map((type) => (
                <button
                  key={type}
                  className={activeFilter === type ? 'active' : ''}
                  onClick={() => setActiveFilter(type)}
                  type="button"
                >
                  {type}
                </button>
              ))}
            </div>
            <div className="tenant-table-wrap">
              <table>
                <thead>
                  <tr>
                    <th>User ID</th>
                    <th>Status</th>
                    <th>Role</th>
                    <th>Actions</th>
                  </tr>
                </thead>
                <tbody>
                  {loadingUsers ? (
                    <tr>
                      <td colSpan={4}>Loading users...</td>
                    </tr>
                  ) : null}
                  {!loadingUsers && filteredUsers.length === 0 ? (
                    <tr>
                      <td colSpan={4}>No users found.</td>
                    </tr>
                  ) : null}
                  {filteredUsers.map((user) => (
                    <tr key={user.id}>
                      <td>{user.id}</td>
                      <td>
                        <button
                          className={user.isActive ? 'status active' : 'status inactive'}
                          onClick={() => handleStatusToggle(user.id)}
                          type="button"
                        >
                          {user.isActive ? 'Active' : 'Inactive'}
                        </button>
                      </td>
                      <td>
                        <select
                          value={user.role}
                          onChange={(event) => handleRoleChange(user.id, event.target.value)}
                        >
                          {roles.map((role) => (
                            <option key={role} value={role}>
                              {role}
                            </option>
                          ))}
                        </select>
                      </td>
                      <td>
                        <button className="remove-btn" onClick={() => handleRemoveUser(user.id)} type="button">
                          Remove
                        </button>
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          </article>

          <div className="tenant-side-column">
            <article className="tenant-card catalogue-card">
              <div className="catalogue-card-head">
                <div>
                  <h2>Catalogue Review</h2>
                  <p>Review generated catalogues and approve the right one for live querying.</p>
                </div>
                <button type="button" onClick={refreshCatalogues} disabled={loadingCatalogues}>
                  {loadingCatalogues ? 'Refreshing...' : 'Refresh'}
                </button>
              </div>
              <div className="catalogue-generate-row">
                <button type="button" onClick={handleGenerateCatalogue} disabled={generatingCatalogue}>
                  {generatingCatalogue ? 'Generating...' : 'Generate Catalogue'}
                </button>
                <small>Uses the connected BigQuery source for this tenant.</small>
              </div>

              <div className="catalogue-list-wrap">
                {loadingCatalogues ? (
                  <p>Loading catalogues...</p>
                ) : catalogues.length === 0 ? (
                  <p>No catalogues found for this tenant yet.</p>
                ) : (
                  <ul className="catalogue-list">
                    {catalogues.map((catalogue) => (
                      <li key={catalogue.id}>
                        <div>
                          <strong>#{catalogue.id}</strong>
                          <span>{catalogue.schemaName}</span>
                        </div>
                        <div className="catalogue-meta">
                          <em className={`catalogue-status ${String(catalogue.status || '').toLowerCase()}`}>
                            {catalogue.status}
                          </em>
                          <small>{catalogue.tableCount} tables</small>
                        </div>
                        <div className="catalogue-actions">
                          <button type="button" onClick={() => handleViewCatalogue(catalogue.id)}>
                            View Catalogue
                          </button>
                          <button
                            type="button"
                            onClick={() => handleApproveCatalogue(catalogue.id, catalogue.clientId)}
                            disabled={
                              catalogueActionId === catalogue.id ||
                              String(catalogue.status || '').toUpperCase() === 'APPROVED'
                            }
                          >
                            {catalogueActionId === catalogue.id ? 'Saving...' : 'Approve'}
                          </button>
                          <button
                            type="button"
                            onClick={() => handleRejectCatalogue(catalogue.id, catalogue.clientId)}
                            disabled={catalogueActionId === catalogue.id}
                          >
                            Reject
                          </button>
                        </div>
                      </li>
                    ))}
                  </ul>
                )}
              </div>

              {catalogueFeedback ? <p className="tenant-feedback">{catalogueFeedback}</p> : null}
              {catalogueError ? <p className="tenant-feedback tenant-error">{catalogueError}</p> : null}
            </article>

            <article className="tenant-card cloud-link-card">
              <h3>Connect BigQuery</h3>
              <p className="connector-help">
                Save full BigQuery connector details so Kontexa can access tenant data directly from cloud.
              </p>
              {bigQueryConnected ? (
                <div className="bigquery-status">
                  <p>
                    <strong>Status:</strong> Connected
                  </p>
                  <p>
                    <strong>Connection Link:</strong> {bigQueryConnectionLink || 'N/A'}
                  </p>
                  <p>
                    <strong>Service Account:</strong>{' '}
                    {serviceAccountStored ? 'Stored securely' : 'Not stored'}
                  </p>
                  <button
                    type="button"
                    className="bigquery-load-btn"
                    onClick={handleLoadBigQueryTables}
                    disabled={loadingBigQueryTables}
                  >
                    {loadingBigQueryTables ? 'Loading Tables...' : 'Load Tables from BigQuery'}
                  </button>
                </div>
              ) : null}
              <form>
                <label htmlFor="bq-project-id">Project ID</label>
                <input
                  id="bq-project-id"
                  type="text"
                  value={bigQueryForm.projectId}
                  onChange={(event) => handleBigQueryField('projectId', event.target.value)}
                  placeholder="my-gcp-project"
                />

                <label htmlFor="bq-location">Location (optional)</label>
                <input
                  id="bq-location"
                  type="text"
                  value={bigQueryForm.location}
                  onChange={(event) => handleBigQueryField('location', event.target.value)}
                  placeholder="US"
                />

                <label htmlFor="bq-dataset">Dataset (optional)</label>
                <input
                  id="bq-dataset"
                  type="text"
                  value={bigQueryForm.dataset}
                  onChange={(event) => handleBigQueryField('dataset', event.target.value)}
                  placeholder="analytics"
                />

                <label htmlFor="bq-service-json">Service Account JSON</label>
                <textarea
                  id="bq-service-json"
                  rows={6}
                  value={bigQueryForm.serviceAccountJson}
                  onChange={(event) => handleBigQueryField('serviceAccountJson', event.target.value)}
                  placeholder='Paste raw JSON or base64 JSON here. Example: {"type":"service_account", ...}'
                />

                <div className="bigquery-actions">
                  <button type="button" onClick={handleTestBigQuery} disabled={testingBigQuery || connectingBigQuery}>
                    {testingBigQuery ? 'Testing...' : 'Test Connection'}
                  </button>
                  <button
                    type="button"
                    onClick={handleConnectBigQuery}
                    disabled={testingBigQuery || connectingBigQuery}
                  >
                    {connectingBigQuery ? 'Connecting...' : 'Connect & Save'}
                  </button>
                </div>
              </form>
              {bigQueryFeedback ? <p className="tenant-feedback">{bigQueryFeedback}</p> : null}
              {bigQueryError ? <p className="tenant-feedback tenant-error">{bigQueryError}</p> : null}
              {bigQueryTables.length > 0 ? (
                <div className="bigquery-table-list">
                  <h4>BigQuery Tables ({bigQueryTables.length})</h4>
                  <ul>
                    {bigQueryTables.map((table) => (
                      <li key={table}>{table}</li>
                    ))}
                  </ul>
                </div>
              ) : null}
            </article>

            <article className="tenant-card add-user-card">
              <h2>Add User</h2>
              <form onSubmit={handleAddUser}>
                <label>User ID</label>
                <input
                  value={newUser.id}
                  onChange={(event) => setNewUser((c) => ({ ...c, id: event.target.value }))}
                  required
                />
                <label>Password</label>
                <input
                  type="password"
                  value={newUser.password}
                  onChange={(event) => setNewUser((c) => ({ ...c, password: event.target.value }))}
                  required
                />
                <label>Role</label>
                <select
                  value={newUser.role}
                  onChange={(event) => setNewUser((c) => ({ ...c, role: event.target.value }))}
                >
                  {roles.map((role) => (
                    <option key={role} value={role}>
                      {role}
                    </option>
                  ))}
                </select>
                <label className="active-checkbox">
                  <input
                    type="checkbox"
                    checked={newUser.isActive}
                    onChange={(event) => setNewUser((c) => ({ ...c, isActive: event.target.checked }))}
                  />
                  Set as active user
                </label>
                <button type="submit" disabled={savingUser}>
                  {savingUser ? 'Adding...' : 'Add User'}
                </button>
              </form>
              {feedback ? <p className="tenant-feedback">{feedback}</p> : null}
              {errorMessage ? <p className="tenant-feedback tenant-error">{errorMessage}</p> : null}
            </article>
          </div>
        </section>
      </main>
    </div>
  );
}

export default TenantDashboardPage;
