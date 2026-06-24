import { useEffect, useMemo, useRef, useState } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import {
  connectTenantBigQuery,
  inviteWorkspaceMember,
  deleteTenantUser,
  fetchTenantBigQueryConfig,
  fetchTenantBigQueryTables,
  fetchTenantUsers,
  fetchWorkspaceMembers,
  resendWorkspaceInvite,
  revokeWorkspaceInvite,
  testTenantBigQueryConnection,
  updateTenantUserRole,
  updateTenantUserStatus,
  updateWorkspaceMemberRole,
  updateWorkspaceMemberStatus,
} from '../api/authApi';
import {
  approveCatalogue,
  buildFullCatalogue,
  listCataloguesForTenantContext,
  rejectCatalogue,
  saveCatalogue,
} from '../api/catalogueApi';
import { clearSession, getSession, saveSession } from '../api/session';
import './TenantDashboardPage.css';

const ROLES = ['ADMIN', 'ANALYST', 'VIEWER'];

const LIFECYCLE_LABELS = {
  ACTIVE: 'Active',
  INVITED: 'Invited',
  EXPIRED: 'Expired',
  REVOKED: 'Revoked',
  SUSPENDED: 'Suspended',
};

function InviteExpiryCountdown({ expiresAt }) {
  const [label, setLabel] = useState('');

  useEffect(() => {
    if (!expiresAt) return undefined;
    function tick() {
      const end = new Date(expiresAt).getTime();
      const diff = end - Date.now();
      if (Number.isNaN(end) || diff <= 0) {
        setLabel('Expired');
        return;
      }
      const hours = Math.floor(diff / 3600000);
      const mins = Math.floor((diff % 3600000) / 60000);
      setLabel(hours > 0 ? `${hours}h ${mins}m left` : `${mins}m left`);
    }
    tick();
    const id = window.setInterval(tick, 30000);
    return () => window.clearInterval(id);
  }, [expiresAt]);

  if (!label) return null;
  return <span className="invite-countdown">{label}</span>;
}

const WAREHOUSE_SOURCES = [
  {
    id: 'bigquery',
    label: 'BigQuery',
    provider: 'Google Cloud',
    category: 'Warehouse',
    available: true,
    color: '#4285F4',
    initials: 'BQ',
    description: 'Query your Google BigQuery datasets using service account credentials.',
  },
  {
    id: 'snowflake',
    label: 'Snowflake',
    provider: 'Snowflake',
    category: 'Warehouse',
    available: false,
    color: '#29B5E8',
    initials: 'SF',
    description: 'Connect to Snowflake using account identifier and OAuth or password auth.',
  },
  {
    id: 'redshift',
    label: 'Redshift',
    provider: 'AWS',
    category: 'Warehouse',
    available: false,
    color: '#C7131F',
    initials: 'RS',
    description: 'Connect to Amazon Redshift clusters using JDBC or Data API.',
  },
  {
    id: 'databricks',
    label: 'Databricks',
    provider: 'Databricks',
    category: 'Warehouse',
    available: false,
    color: '#FF5F1F',
    initials: 'DB',
    description: 'Query Delta Lake tables via Databricks SQL warehouses.',
  },
];

const NAV_ITEMS = [
  { id: 'overview',     label: 'Overview',     icon: '◈' },
  { id: 'connections',  label: 'Connections',  icon: '⬡' },
  { id: 'catalogues',   label: 'Catalogues',   icon: '▤' },
  { id: 'users',        label: 'Users',        icon: '◉' },
];

function TenantDashboardPage() {
  const navigate = useNavigate();
  const tenantSession = getSession() || {};
  const tenantId       = tenantSession.tenantId     || '';
  const tenantSchema   = tenantSession.tenantSchema || '';
  const tenantUserId   = tenantSession.userId       || '';
  const catalogueClientId = tenantSchema || tenantId;
  const profileMenuRef = useRef(null);
  const fileInputRef   = useRef(null);

  // ── UI state ────────────────────────────────────────────────────────────
  const [activeSection,      setActiveSection]      = useState('overview');
  const [profileOpen,        setProfileOpen]        = useState(false);
  const [showAddUser,        setShowAddUser]        = useState(false);
  const [showAdvancedJson,   setShowAdvancedJson]   = useState(false);
  const [activeFilter,       setActiveFilter]       = useState('all');
  const [searchText,         setSearchText]         = useState('');
  const [isDragOver,         setIsDragOver]         = useState(false);
  const [credentialFileName, setCredentialFileName] = useState('');
  const [credentialValid,    setCredentialValid]    = useState(null);
  const [selectedWarehouse,  setSelectedWarehouse]  = useState('bigquery');

  // ── Data state ───────────────────────────────────────────────────────────
  const [users,                setUsers]                = useState([]);
  const [loadingUsers,         setLoadingUsers]         = useState(true);
  const [savingUser,           setSavingUser]           = useState(false);
  const [newUser,              setNewUser]              = useState({ id: '', password: '', role: 'VIEWER', isActive: true });
  const [feedback,             setFeedback]             = useState('');
  const [errorMessage,         setErrorMessage]         = useState('');
  const [inviteActionId,       setInviteActionId]       = useState(null);

  const [bigQueryConnected,    setBigQueryConnected]    = useState(false);
  const [bigQueryConnectionLink, setBigQueryConnectionLink] = useState('');
  const [serviceAccountStored, setServiceAccountStored] = useState(false);
  const [bigQueryTables,       setBigQueryTables]       = useState([]);
  const [bigQueryFeedback,     setBigQueryFeedback]     = useState('');
  const [bigQueryError,        setBigQueryError]        = useState('');
  const [testingBigQuery,      setTestingBigQuery]      = useState(false);
  const [connectingBigQuery,   setConnectingBigQuery]   = useState(false);
  const [loadingBigQueryTables, setLoadingBigQueryTables] = useState(false);
  const [bigQueryForm,         setBigQueryForm]         = useState({
    projectId: '', location: '', dataset: '', serviceAccountJson: '',
  });

  const [catalogues,           setCatalogues]           = useState([]);
  const [loadingCatalogues,    setLoadingCatalogues]    = useState(false);
  const [catalogueFeedback,    setCatalogueFeedback]    = useState('');
  const [catalogueError,       setCatalogueError]       = useState('');
  const [catalogueActionId,    setCatalogueActionId]    = useState(null);
  const [generatingCatalogue,  setGeneratingCatalogue]  = useState(false);

  // ── Profile menu close-on-outside-click ──────────────────────────────────
  useEffect(() => {
    function onWindowClick(event) {
      if (profileMenuRef.current && !profileMenuRef.current.contains(event.target)) {
        setProfileOpen(false);
      }
    }
    if (profileOpen) window.addEventListener('click', onWindowClick);
    return () => window.removeEventListener('click', onWindowClick);
  }, [profileOpen]);

  const loadTeamMembers = async () => {
    const session = getSession();
    setLoadingUsers(true);
    try {
      if (session?.accessToken) {
        const usersPayload = await fetchWorkspaceMembers();
        setUsers((usersPayload.members || []).map((m) => ({
          id: m.email,
          identityId: m.identityId,
          displayName: m.displayName,
          role: m.role || 'VIEWER',
          lifecycleStatus: m.lifecycleStatus || 'ACTIVE',
          inviteId: m.inviteId,
          expiresAt: m.expiresAt,
          isActive: m.lifecycleStatus === 'ACTIVE',
          enterprise: true,
        })));
      } else if (tenantId) {
        const usersPayload = await fetchTenantUsers(tenantId);
        setUsers((usersPayload.users || []).map((u) => ({
          id: u.user_id || u.userId,
          role: u.position || 'Viewer',
          lifecycleStatus: u.active ? 'ACTIVE' : 'SUSPENDED',
          isActive: Boolean(u.active),
          enterprise: false,
        })));
      }
    } catch (err) {
      setErrorMessage(err.message || 'Failed to load team members');
    } finally {
      setLoadingUsers(false);
    }
  };

  // ── Initial data load ────────────────────────────────────────────────────
  useEffect(() => {
    if (!tenantId) return;
    async function loadData() {
      setLoadingCatalogues(true);
      try {
        const [_, bigQueryPayload, cataloguePayload] = await Promise.all([
          loadTeamMembers(),
          fetchTenantBigQueryConfig(tenantId).catch(() => ({ connected: false })),
          (tenantId || tenantSchema)
            ? listCataloguesForTenantContext({ tenantId, tenantSchema }).catch(() => [])
            : Promise.resolve([]),
        ]);
        setBigQueryConnected(Boolean(bigQueryPayload.connected));
        setBigQueryConnectionLink(bigQueryPayload.connectionLink || '');
        setServiceAccountStored(Boolean(bigQueryPayload.serviceAccountStored));
        setBigQueryTables([]);
        setBigQueryForm((c) => ({
          ...c,
          projectId: bigQueryPayload.projectId  || '',
          location:  bigQueryPayload.location   || '',
          dataset:   bigQueryPayload.dataset    || '',
          serviceAccountJson: '',
        }));
        setCatalogues(cataloguePayload);
      } catch (err) {
        setErrorMessage(err.message || 'Failed to load workspace data');
      } finally {
        setLoadingCatalogues(false);
      }
    }
    loadData();
  }, [tenantId, tenantSchema, catalogueClientId]);

  // ── Derived values ───────────────────────────────────────────────────────
  const filteredUsers = useMemo(
    () => users.filter((u) => {
      const status = u.lifecycleStatus || (u.isActive ? 'ACTIVE' : 'SUSPENDED');
      const match = activeFilter === 'all'
        ? true
        : activeFilter === 'active'
          ? status === 'ACTIVE'
          : activeFilter === 'invited'
            ? status === 'INVITED' || status === 'EXPIRED'
            : status === 'SUSPENDED' || status === 'REVOKED' || !u.isActive;
      const q = searchText.toLowerCase();
      return match && (
        u.id.toLowerCase().includes(q)
        || (u.displayName || '').toLowerCase().includes(q)
      );
    }),
    [users, activeFilter, searchText]
  );
  const approvedCatalogue = catalogues.find((c) => String(c.status || '').toUpperCase() === 'APPROVED');
  const activeUserCount   = users.filter((u) => u.lifecycleStatus === 'ACTIVE' || u.isActive).length;
  const invitedUserCount  = users.filter((u) => u.lifecycleStatus === 'INVITED' || u.lifecycleStatus === 'EXPIRED').length;

  // ── User handlers ────────────────────────────────────────────────────────
  const handleAddUser = async (event) => {
    event.preventDefault();
    if (!tenantId) return;
    const session = getSession();
    if (!session?.accessToken) {
      setFeedback('Your session has no auth token. Sign out and sign in again, then retry.');
      return;
    }
    setFeedback('');
    try {
      setSavingUser(true);
      const email = newUser.id.trim();
      const invite = await inviteWorkspaceMember({
        email,
        role: newUser.role,
      });
      setNewUser({ id: '', password: '', role: 'VIEWER', isActive: true });
      const emailNote = invite.emailSent === false
        ? ' (email delivery is disabled — check server logs for activation link)'
        : '';
      setFeedback(`Invitation email sent to ${email}${emailNote}`);
      setShowAddUser(false);
      await loadTeamMembers();
    } catch (err) {
      setFeedback(err.message || 'Failed to send invite');
    } finally {
      setSavingUser(false);
    }
  };

  const handleRoleChange = async (userId, role) => {
    const target = users.find((x) => x.id === userId);
    if (!target) return;
    try {
      if (target.enterprise && target.identityId) {
        await updateWorkspaceMemberRole({ identityId: target.identityId, role });
      } else {
        await updateTenantUserRole({ tenantId, userId, position: role });
      }
      setUsers((c) => c.map((u) => (u.id === userId ? { ...u, role } : u)));
      setFeedback('Role updated');
    } catch (err) {
      setFeedback(err.message || 'Failed to update role');
    }
  };

  const handleStatusToggle = async (userId) => {
    const target = users.find((x) => x.id === userId);
    if (!target) return;
    try {
      const nextActive = !target.isActive;
      if (target.enterprise && target.identityId) {
        await updateWorkspaceMemberStatus({ identityId: target.identityId, active: nextActive });
        setUsers((c) => c.map((u) => (u.id === userId
          ? { ...u, isActive: nextActive, lifecycleStatus: nextActive ? 'ACTIVE' : 'SUSPENDED' }
          : u)));
      } else {
        await updateTenantUserStatus({ tenantId, userId, active: nextActive });
        setUsers((c) => c.map((u) => (u.id === userId ? { ...u, isActive: nextActive } : u)));
      }
      setFeedback(nextActive ? 'Member activated' : 'Member suspended');
    } catch (err) {
      setFeedback(err.message || 'Failed to update status');
    }
  };

  const handleResendInvite = async (inviteId) => {
    if (!inviteId) return;
    try {
      setInviteActionId(inviteId);
      setFeedback('');
      await resendWorkspaceInvite(inviteId);
      setFeedback('Invitation resent successfully');
      await loadTeamMembers();
    } catch (err) {
      setFeedback(err.message || 'Failed to resend invite');
    } finally {
      setInviteActionId(null);
    }
  };

  const handleRevokeInvite = async (inviteId) => {
    if (!inviteId) return;
    try {
      setInviteActionId(inviteId);
      setFeedback('');
      await revokeWorkspaceInvite(inviteId);
      setFeedback('Invitation revoked');
      await loadTeamMembers();
    } catch (err) {
      setFeedback(err.message || 'Failed to revoke invite');
    } finally {
      setInviteActionId(null);
    }
  };

  const handleRemoveUser = async (userId) => {
    try {
      await deleteTenantUser({ tenantId, userId });
      setUsers((c) => c.filter((u) => u.id !== userId));
    } catch (err) {
      setFeedback(err.message || 'Failed to remove user');
    }
  };

  // ── BigQuery handlers ────────────────────────────────────────────────────
  const handleBigQueryField = (key, value) => setBigQueryForm((c) => ({ ...c, [key]: value }));

  const handleFileUpload = (file) => {
    if (!file) return;
    setCredentialFileName(file.name);
    if (!file.name.endsWith('.json')) { setCredentialValid(false); return; }
    const reader = new FileReader();
    reader.onload = (e) => {
      try {
        JSON.parse(e.target.result);
        setBigQueryForm((c) => ({ ...c, serviceAccountJson: e.target.result }));
        setCredentialValid(true);
      } catch {
        setCredentialValid(false);
      }
    };
    reader.readAsText(file);
  };

  const handleDrop = (e) => {
    e.preventDefault();
    setIsDragOver(false);
    const file = e.dataTransfer.files[0];
    if (file) handleFileUpload(file);
  };

  const handleTestBigQuery = async (event) => {
    event.preventDefault();
    if (!bigQueryForm.projectId.trim() || !bigQueryForm.serviceAccountJson.trim()) {
      setBigQueryError('Project ID and Service Account JSON are required.');
      return;
    }
    setBigQueryFeedback(''); setBigQueryError('');
    try {
      setTestingBigQuery(true);
      const payload = await testTenantBigQueryConnection({
        projectId: bigQueryForm.projectId.trim(),
        serviceAccountJson: bigQueryForm.serviceAccountJson.trim(),
        location: bigQueryForm.location.trim(),
        dataset:  bigQueryForm.dataset.trim(),
      });
      setBigQueryFeedback(`Connection successful. Link: ${payload.connectionLink || ''}`);
    } catch (err) {
      setBigQueryError(err.message || 'BigQuery test failed');
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
    setBigQueryFeedback(''); setBigQueryError('');
    try {
      setConnectingBigQuery(true);
      const payload = await connectTenantBigQuery({
        tenantId,
        projectId: bigQueryForm.projectId.trim(),
        serviceAccountJson: bigQueryForm.serviceAccountJson.trim(),
        location: bigQueryForm.location.trim(),
        dataset:  bigQueryForm.dataset.trim(),
      });
      const savedLink = payload.cloudDbLink || '';
      setBigQueryConnectionLink(savedLink);
      setBigQueryConnected(true);
      setServiceAccountStored(true);
      setBigQueryTables([]);
      saveSession({ ...tenantSession, cloudDbLink: savedLink });
      setBigQueryForm((c) => ({ ...c, serviceAccountJson: '' }));
      setCredentialFileName('');
      setCredentialValid(null);
      setBigQueryFeedback('BigQuery connected and configuration saved successfully.');
    } catch (err) {
      setBigQueryError(err.message || 'Failed to connect BigQuery');
    } finally {
      setConnectingBigQuery(false);
    }
  };

  const handleLoadBigQueryTables = async () => {
    if (!tenantId) return;
    setBigQueryError(''); setBigQueryFeedback('');
    try {
      setLoadingBigQueryTables(true);
      const payload = await fetchTenantBigQueryTables(tenantId);
      setBigQueryTables(payload.tables || []);
      setBigQueryFeedback(`Loaded ${payload.tableCount || 0} tables from BigQuery.`);
    } catch (err) {
      setBigQueryError(err.message || 'Failed to load BigQuery tables');
    } finally {
      setLoadingBigQueryTables(false);
    }
  };

  // ── Catalogue handlers ───────────────────────────────────────────────────
  const refreshCatalogues = async () => {
    if (!tenantId && !tenantSchema) return;
    setLoadingCatalogues(true);
    setCatalogueError('');
    try {
      const payload = await listCataloguesForTenantContext({ tenantId, tenantSchema });
      setCatalogues(payload);
    } catch (err) {
      setCatalogueError(err.message || 'Failed to refresh catalogues');
    } finally {
      setLoadingCatalogues(false);
    }
  };

  const handleViewCatalogue = (catalogueId) => navigate(`/tenant/catalogue/${catalogueId}`);

  const handleGenerateCatalogue = async () => {
    if (!tenantId) return;
    setGeneratingCatalogue(true);
    setCatalogueError(''); setCatalogueFeedback('');
    try {
      const built = await buildFullCatalogue({ schema: tenantSchema || catalogueClientId, tenantId, clientId: catalogueClientId });
      const savePayload = await saveCatalogue({ clientId: catalogueClientId, catalogueResult: built });
      setCatalogueFeedback(`Catalogue generated and saved as draft (#${savePayload.id}). Review and approve it below.`);
      await refreshCatalogues();
    } catch (err) {
      setCatalogueError(err.message || 'Failed to generate catalogue');
    } finally {
      setGeneratingCatalogue(false);
    }
  };

  const handleApproveCatalogue = async (catalogueId, catalogueClient) => {
    if (!catalogueClient) return;
    setCatalogueActionId(catalogueId); setCatalogueError('');
    try {
      await approveCatalogue(catalogueId, catalogueClient);
      setCatalogueFeedback(`Catalogue #${catalogueId} approved successfully.`);
      await refreshCatalogues();
    } catch (err) {
      setCatalogueError(err.message || 'Failed to approve catalogue');
    } finally {
      setCatalogueActionId(null);
    }
  };

  const handleRejectCatalogue = async (catalogueId, catalogueClient) => {
    if (!catalogueClient) return;
    setCatalogueActionId(catalogueId); setCatalogueError('');
    try {
      await rejectCatalogue(catalogueId, catalogueClient);
      setCatalogueFeedback(`Catalogue #${catalogueId} marked as rejected.`);
      await refreshCatalogues();
    } catch (err) {
      setCatalogueError(err.message || 'Failed to reject catalogue');
    } finally {
      setCatalogueActionId(null);
    }
  };

  const handleSignOut = () => {
    clearSession();
    navigate('/signin');
  };

  // ── Section: Overview ────────────────────────────────────────────────────
  const renderOverview = () => (
    <div className="section-body">
      <div className="stat-grid">
        <div className="stat-card">
          <span className="stat-label">Total Users</span>
          <span className="stat-value">{loadingUsers ? '—' : users.length}</span>
          <span className="stat-sub">
            {activeUserCount} active{invitedUserCount > 0 ? ` · ${invitedUserCount} invited` : ''}
          </span>
        </div>
        <div className="stat-card">
          <span className="stat-label">Catalogues</span>
          <span className="stat-value">{catalogues.length}</span>
          <span className="stat-sub">{approvedCatalogue ? '1 approved' : 'none approved'}</span>
        </div>
        <div className={`stat-card ${bigQueryConnected ? 'stat-card--success' : 'stat-card--warn'}`}>
          <span className="stat-label">Data Source</span>
          <span className="stat-value stat-value--sm">{bigQueryConnected ? 'Connected' : 'Not connected'}</span>
          <span className="stat-sub">BigQuery</span>
        </div>
        <div className={`stat-card ${approvedCatalogue ? 'stat-card--success' : 'stat-card--muted'}`}>
          <span className="stat-label">Workspace</span>
          <span className="stat-value stat-value--sm">{approvedCatalogue ? 'Live' : 'Setup required'}</span>
          <span className="stat-sub">{approvedCatalogue ? `Catalogue #${approvedCatalogue.id}` : 'no catalogue active'}</span>
        </div>
      </div>

      {!bigQueryConnected ? (
        <div className="cta-banner">
          <div className="cta-banner-left">
            <p className="cta-banner-title">Connect your data warehouse</p>
            <p className="cta-banner-sub">Link BigQuery to start generating catalogues and enabling analysis.</p>
          </div>
          <button className="btn-primary" onClick={() => setActiveSection('connections')}>
            Connect BigQuery →
          </button>
        </div>
      ) : !approvedCatalogue ? (
        <div className="cta-banner cta-banner--connected">
          <div className="cta-banner-left">
            <p className="cta-banner-title">Generate your first catalogue</p>
            <p className="cta-banner-sub">BigQuery is connected. Generate and approve a catalogue to enable your team.</p>
          </div>
          <button className="btn-primary" onClick={() => setActiveSection('catalogues')}>
            Manage Catalogues →
          </button>
        </div>
      ) : (
        <div className="cta-banner cta-banner--live">
          <div className="status-pulse" />
          <div className="cta-banner-left">
            <p className="cta-banner-title">Workspace is live</p>
            <p className="cta-banner-sub">Catalogue #{approvedCatalogue.id} is approved. Your team can now query data.</p>
          </div>
        </div>
      )}

      <div className="overview-grid">
        <div className="overview-card">
          <div className="overview-card-head">
            <span className="overview-card-title">Recent Catalogues</span>
            <button className="btn-link" onClick={() => setActiveSection('catalogues')}>View all</button>
          </div>
          {loadingCatalogues ? (
            <p className="muted-text">Loading…</p>
          ) : catalogues.length === 0 ? (
            <p className="muted-text">No catalogues yet.</p>
          ) : (
            <div className="recent-list">
              {catalogues.slice(0, 4).map((cat) => (
                <div key={cat.id} className="recent-row">
                  <div className="recent-row-left">
                    <span className="recent-id">#{cat.id}</span>
                    <span className="recent-name">{cat.schemaName}</span>
                  </div>
                  <span className={`status-badge status-${String(cat.status || '').toLowerCase()}`}>
                    {cat.status}
                  </span>
                </div>
              ))}
            </div>
          )}
        </div>

        <div className="overview-card">
          <div className="overview-card-head">
            <span className="overview-card-title">Team</span>
            <button className="btn-link" onClick={() => setActiveSection('users')}>Manage</button>
          </div>
          {loadingUsers ? (
            <p className="muted-text">Loading…</p>
          ) : users.length === 0 ? (
            <p className="muted-text">No users yet.</p>
          ) : (
            <div className="recent-list">
              {users.slice(0, 5).map((u) => (
                <div key={u.id} className="recent-row">
                  <div className="recent-row-left">
                    <span className="user-avatar-sm">{u.id[0]?.toUpperCase()}</span>
                    <span className="recent-name">{u.id}</span>
                  </div>
                  <span className={`role-badge role-${u.role.toLowerCase()}`}>{u.role}</span>
                </div>
              ))}
            </div>
          )}
        </div>
      </div>
    </div>
  );

  // ── Section: Connections ─────────────────────────────────────────────────
  const renderBigQueryConfig = () => (
    <div className="connector-steps">
      <div className="connector-step">
        <div className="step-badge">1</div>
        <div className="step-content">
          <p className="step-title">Project Configuration</p>
          <div className="step-fields">
            <div className="field-group">
              <label className="field-label">Project ID <span className="field-required">*</span></label>
              <input
                className="field-input"
                type="text"
                value={bigQueryForm.projectId}
                onChange={(e) => handleBigQueryField('projectId', e.target.value)}
                placeholder="my-gcp-project"
              />
            </div>
            <div className="field-row">
              <div className="field-group">
                <label className="field-label">Location <span className="field-optional">optional</span></label>
                <input
                  className="field-input"
                  type="text"
                  value={bigQueryForm.location}
                  onChange={(e) => handleBigQueryField('location', e.target.value)}
                  placeholder="US"
                />
              </div>
              <div className="field-group">
                <label className="field-label">Dataset <span className="field-optional">optional</span></label>
                <input
                  className="field-input"
                  type="text"
                  value={bigQueryForm.dataset}
                  onChange={(e) => handleBigQueryField('dataset', e.target.value)}
                  placeholder="analytics"
                />
              </div>
            </div>
          </div>
        </div>
      </div>

      <div className="connector-step">
        <div className="step-badge">2</div>
        <div className="step-content">
          <p className="step-title">Service Account Credentials <span className="field-required">*</span></p>
          <div
            className={`dropzone ${isDragOver ? 'dropzone--over' : ''} ${credentialValid === true ? 'dropzone--valid' : ''} ${credentialValid === false ? 'dropzone--invalid' : ''}`}
            onDragOver={(e) => { e.preventDefault(); setIsDragOver(true); }}
            onDragLeave={() => setIsDragOver(false)}
            onDrop={handleDrop}
            onClick={() => fileInputRef.current?.click()}
            role="button"
            tabIndex={0}
            onKeyDown={(e) => e.key === 'Enter' && fileInputRef.current?.click()}
          >
            {credentialFileName ? (
              <div className="dropzone-success-content">
                <span className="dropzone-file-icon">{credentialValid === true ? '✓' : '✕'}</span>
                <div>
                  <p className="dropzone-filename">{credentialFileName}</p>
                  <p className={credentialValid === true ? 'dropzone-valid-msg' : 'dropzone-invalid-msg'}>
                    {credentialValid === true ? 'Valid JSON credentials' : 'Invalid JSON — please check the file'}
                  </p>
                </div>
              </div>
            ) : (
              <div className="dropzone-empty-content">
                <span className="dropzone-icon">↑</span>
                <p className="dropzone-primary">Drop your service account JSON here</p>
                <p className="dropzone-secondary">or click to browse files</p>
              </div>
            )}
          </div>
          <input
            ref={fileInputRef}
            type="file"
            accept=".json"
            style={{ display: 'none' }}
            onChange={(e) => handleFileUpload(e.target.files?.[0])}
          />
          <button
            className="btn-text-toggle"
            type="button"
            onClick={() => setShowAdvancedJson((v) => !v)}
          >
            {showAdvancedJson ? '▾ Hide' : '▸ Advanced'}: paste JSON manually
          </button>
          {showAdvancedJson && (
            <textarea
              className="field-textarea"
              rows={7}
              value={bigQueryForm.serviceAccountJson}
              onChange={(e) => handleBigQueryField('serviceAccountJson', e.target.value)}
              placeholder='{"type":"service_account","project_id":"...","private_key":"..."}'
            />
          )}
        </div>
      </div>

      <div className="connector-step">
        <div className="step-badge">3</div>
        <div className="step-content">
          <p className="step-title">Test & Activate</p>
          <p className="step-sub">Validate credentials before saving to ensure the connection works.</p>
          <div className="step-actions">
            <button
              className="btn-secondary"
              type="button"
              onClick={handleTestBigQuery}
              disabled={testingBigQuery || connectingBigQuery}
            >
              {testingBigQuery ? 'Testing…' : 'Test Connection'}
            </button>
            <button
              className="btn-primary"
              type="button"
              onClick={handleConnectBigQuery}
              disabled={testingBigQuery || connectingBigQuery}
            >
              {connectingBigQuery ? 'Connecting…' : 'Connect & Save'}
            </button>
          </div>
          {bigQueryFeedback && <p className="feedback-msg feedback-success">{bigQueryFeedback}</p>}
          {bigQueryError    && <p className="feedback-msg feedback-error">{bigQueryError}</p>}
        </div>
      </div>
    </div>
  );

  const renderConnections = () => {
    const selected = WAREHOUSE_SOURCES.find((w) => w.id === selectedWarehouse);
    const warehouseGroup  = WAREHOUSE_SOURCES.filter((w) => w.category === 'Warehouse');

    return (
      <div className="connections-layout">
        {/* ── Left: connected status + source picker ── */}
        <div className="connections-left">
          {bigQueryConnected && (
            <div className="conn-active-banner">
              <div className="conn-dot connected" />
              <div className="conn-active-text">
                <span className="conn-active-label">BigQuery</span>
                <span className="conn-active-sub">Connected</span>
              </div>
              <button
                className="btn-load-tables"
                onClick={handleLoadBigQueryTables}
                disabled={loadingBigQueryTables}
              >
                {loadingBigQueryTables ? '…' : 'Load Tables'}
              </button>
            </div>
          )}

          <div className="source-search-row">
            <p className="source-section-label">Warehouse</p>
          </div>
          <div className="source-list">
            {warehouseGroup.map((src) => (
              <button
                key={src.id}
                className={`source-item ${selectedWarehouse === src.id ? 'active' : ''} ${!src.available ? 'unavailable' : ''}`}
                onClick={() => src.available && setSelectedWarehouse(src.id)}
                type="button"
              >
                <span
                  className="source-icon"
                  style={{ background: src.available ? src.color + '22' : 'rgba(255,255,255,0.04)', color: src.available ? src.color : 'var(--text-3)', borderColor: src.available ? src.color + '44' : 'var(--border)' }}
                >
                  {src.initials}
                </span>
                <span className="source-label">{src.label}</span>
                {bigQueryConnected && src.id === 'bigquery' && (
                  <span className="source-conn-dot" />
                )}
                {!src.available && <span className="source-soon">Soon</span>}
              </button>
            ))}
          </div>

        </div>

        {/* ── Right: config panel ── */}
        <div className="connections-right">
          {selected ? (
            <div className="connector-panel">
              <div className="connector-panel-head">
                <span
                  className="connector-panel-icon"
                  style={{ background: selected.color + '20', color: selected.color, borderColor: selected.color + '40' }}
                >
                  {selected.initials}
                </span>
                <div>
                  <h2 className="connector-title">{selected.label}</h2>
                  <p className="connector-provider">{selected.provider}</p>
                </div>
              </div>

              <p className="connector-sub">{selected.description}</p>

              {selected.available ? (
                <>
                  {renderBigQueryConfig()}
                  <div className="security-note">
                    <span className="security-dot" />
                    Credentials are encrypted at rest and only used for approved tenant queries.
                  </div>
                  {bigQueryTables.length > 0 && (
                    <div className="tables-card" style={{ marginTop: '16px' }}>
                      <p className="tables-card-title">Discovered Tables ({bigQueryTables.length})</p>
                      <div className="tables-grid">
                        {bigQueryTables.map((t) => (
                          <span key={t} className="table-chip">{t}</span>
                        ))}
                      </div>
                    </div>
                  )}
                </>
              ) : (
                <div className="coming-soon-panel">
                  <span className="coming-soon-icon">⏳</span>
                  <p className="coming-soon-title">{selected.label} is coming soon</p>
                  <p className="coming-soon-sub">
                    We're building native support for {selected.label}. BigQuery is currently the only supported warehouse.
                  </p>
                  <button className="btn-secondary" onClick={() => setSelectedWarehouse('bigquery')}>
                    Use BigQuery instead
                  </button>
                </div>
              )}
            </div>
          ) : (
            <div className="connector-panel connector-panel--empty">
              <p className="muted-text">Select a data source from the left to get started.</p>
            </div>
          )}
        </div>
      </div>
    );
  };

  // ── Section: Catalogues ──────────────────────────────────────────────────
  const renderCatalogues = () => (
    <div className="section-body">
      <div className="section-actions-bar">
        <div>
          <p className="section-actions-desc">
            {catalogues.length} catalogue{catalogues.length !== 1 ? 's' : ''} for this workspace
          </p>
        </div>
        <div className="section-actions-right">
          <button className="btn-secondary" onClick={refreshCatalogues} disabled={loadingCatalogues}>
            {loadingCatalogues ? 'Refreshing…' : 'Refresh'}
          </button>
          <button
            className="btn-primary"
            onClick={handleGenerateCatalogue}
            disabled={generatingCatalogue || !bigQueryConnected}
            title={!bigQueryConnected ? 'Connect BigQuery first' : ''}
          >
            {generatingCatalogue ? 'Generating…' : '+ Generate Catalogue'}
          </button>
        </div>
      </div>

      {!bigQueryConnected && (
        <div className="inline-notice">
          <span>BigQuery is not connected.</span>
          <button className="btn-link" onClick={() => setActiveSection('connections')}>Connect now →</button>
        </div>
      )}

      {catalogueFeedback && <p className="feedback-msg feedback-success">{catalogueFeedback}</p>}
      {catalogueError    && <p className="feedback-msg feedback-error">{catalogueError}</p>}

      {loadingCatalogues ? (
        <p className="muted-text">Loading catalogues…</p>
      ) : catalogues.length === 0 ? (
        <div className="empty-state">
          <span className="empty-icon">▤</span>
          <p className="empty-title">No catalogues yet</p>
          <p className="empty-sub">Generate your first catalogue from the connected BigQuery source.</p>
        </div>
      ) : (
        <div className="catalogue-grid">
          {catalogues.map((cat) => {
            const statusKey = String(cat.status || '').toLowerCase();
            const isApproved = statusKey === 'approved';
            return (
              <div key={cat.id} className={`catalogue-item ${isApproved ? 'catalogue-item--approved' : ''}`}>
                <div className="catalogue-item-head">
                  <div className="catalogue-item-meta">
                    <span className="catalogue-num">#{cat.id}</span>
                    <span className={`status-badge status-${statusKey}`}>{cat.status}</span>
                  </div>
                  {isApproved && <span className="live-indicator">● Live</span>}
                </div>
                <p className="catalogue-schema-name">{cat.schemaName}</p>
                <p className="catalogue-table-count">{cat.tableCount} tables</p>
                <div className="catalogue-item-actions">
                  <button className="btn-ghost" onClick={() => handleViewCatalogue(cat.id)}>
                    View
                  </button>
                  <button
                    className="btn-approve"
                    onClick={() => handleApproveCatalogue(cat.id, cat.clientId)}
                    disabled={catalogueActionId === cat.id || isApproved}
                  >
                    {catalogueActionId === cat.id ? 'Saving…' : 'Approve'}
                  </button>
                  <button
                    className="btn-reject"
                    onClick={() => handleRejectCatalogue(cat.id, cat.clientId)}
                    disabled={catalogueActionId === cat.id}
                  >
                    Reject
                  </button>
                </div>
              </div>
            );
          })}
        </div>
      )}
    </div>
  );

  // ── Section: Users ───────────────────────────────────────────────────────
  const renderUsers = () => (
    <div className="section-body">
      <div className="section-actions-bar">
        <div className="user-filter-row">
          {['all', 'active', 'invited', 'inactive'].map((f) => (
            <button
              key={f}
              className={`filter-pill ${activeFilter === f ? 'active' : ''}`}
              onClick={() => setActiveFilter(f)}
              type="button"
            >
              {f}
            </button>
          ))}
          <input
            className="search-input"
            placeholder="Search by email or name…"
            value={searchText}
            onChange={(e) => setSearchText(e.target.value)}
          />
        </div>
        <button className="btn-primary" onClick={() => setShowAddUser((v) => !v)}>
          {showAddUser ? '✕ Cancel' : '+ Invite User'}
        </button>
      </div>

      {showAddUser && (
        <div className="add-user-panel">
          <p className="add-user-panel-title">Invite team member</p>
          <form className="add-user-form" onSubmit={handleAddUser}>
            <div className="field-row">
              <div className="field-group">
                <label className="field-label">Email</label>
                <input
                  className="field-input"
                  type="email"
                  value={newUser.id}
                  onChange={(e) => setNewUser((c) => ({ ...c, id: e.target.value }))}
                  placeholder="colleague@company.com"
                  required
                />
              </div>
              <div className="field-group">
                <label className="field-label">Role</label>
                <select
                  className="field-select"
                  value={newUser.role}
                  onChange={(e) => setNewUser((c) => ({ ...c, role: e.target.value }))}
                >
                  {ROLES.map((r) => <option key={r} value={r}>{r}</option>)}
                </select>
              </div>
            </div>
            <div className="add-user-form-foot">
              <p className="section-actions-desc">
                An invitation email is sent automatically. The member activates their account within 72 hours.
              </p>
              <button className="btn-primary" type="submit" disabled={savingUser}>
                {savingUser ? 'Sending…' : 'Send invite'}
              </button>
            </div>
          </form>
          {feedback      && <p className="feedback-msg feedback-success">{feedback}</p>}
          {errorMessage  && <p className="feedback-msg feedback-error">{errorMessage}</p>}
        </div>
      )}

      {!showAddUser && feedback     && <p className="feedback-msg feedback-success">{feedback}</p>}
      {!showAddUser && errorMessage && <p className="feedback-msg feedback-error">{errorMessage}</p>}

      {loadingUsers ? (
        <p className="muted-text">Loading users…</p>
      ) : filteredUsers.length === 0 ? (
        <div className="empty-state">
          <span className="empty-icon">◉</span>
          <p className="empty-title">No users found</p>
          <p className="empty-sub">Try a different filter or add a new user.</p>
        </div>
      ) : (
        <div className="user-list">
          {filteredUsers.map((user) => {
            const status = user.lifecycleStatus || (user.isActive ? 'ACTIVE' : 'SUSPENDED');
            const statusKey = status.toLowerCase();
            const isPendingInvite = status === 'INVITED' || status === 'EXPIRED';
            const isRevoked = status === 'REVOKED';
            return (
              <div key={`${user.id}-${user.identityId || ''}`} className="user-card">
                <div className="user-card-left">
                  <div className="user-avatar">{user.id[0]?.toUpperCase()}</div>
                  <div className="user-info">
                    <span className="user-id-text">{user.id}</span>
                    {user.displayName && user.displayName !== user.id && (
                      <span className="user-display-name">{user.displayName}</span>
                    )}
                    <div className="user-status-row">
                      <span className={`user-status status-${statusKey}`}>
                        {LIFECYCLE_LABELS[status] || status}
                      </span>
                      {isPendingInvite && user.expiresAt && (
                        <InviteExpiryCountdown expiresAt={user.expiresAt} />
                      )}
                    </div>
                  </div>
                </div>
                <div className="user-card-right">
                  {!isPendingInvite && !isRevoked && (
                    <select
                      className="role-select"
                      value={user.role}
                      onChange={(e) => handleRoleChange(user.id, e.target.value)}
                    >
                      {ROLES.map((r) => <option key={r} value={r}>{r}</option>)}
                    </select>
                  )}
                  {isPendingInvite && user.inviteId && (
                    <>
                      <button
                        className="btn-invite-action"
                        onClick={() => handleResendInvite(user.inviteId)}
                        type="button"
                        disabled={inviteActionId === user.inviteId}
                      >
                        {inviteActionId === user.inviteId ? 'Sending…' : 'Resend'}
                      </button>
                      <button
                        className="btn-invite-action btn-invite-revoke"
                        onClick={() => handleRevokeInvite(user.inviteId)}
                        type="button"
                        disabled={inviteActionId === user.inviteId}
                      >
                        Revoke
                      </button>
                    </>
                  )}
                  {status === 'ACTIVE' && (
                    <button
                      className="btn-status-toggle btn-status-active"
                      onClick={() => handleStatusToggle(user.id)}
                      type="button"
                    >
                      Suspend
                    </button>
                  )}
                  {status === 'SUSPENDED' && (
                    <button
                      className="btn-status-toggle btn-status-inactive"
                      onClick={() => handleStatusToggle(user.id)}
                      type="button"
                    >
                      Reactivate
                    </button>
                  )}
                  {!user.enterprise && (
                    <button className="btn-remove" onClick={() => handleRemoveUser(user.id)} type="button">
                      Remove
                    </button>
                  )}
                </div>
              </div>
            );
          })}
        </div>
      )}
    </div>
  );

  const PAGE_META = {
    overview:    { title: 'Overview',    sub: 'Workspace health and status at a glance' },
    connections: { title: 'Connections', sub: 'Configure your data warehouse connection' },
    catalogues:  { title: 'Catalogues',  sub: 'Generate, review, and approve catalogues' },
    users:       { title: 'Users',       sub: 'Manage team members and permissions' },
  };

  const current = PAGE_META[activeSection] || PAGE_META.overview;

  return (
    <div className="admin-shell">
      {/* ── Sidebar ──────────────────────────────────────────────────── */}
      <nav className="admin-sidebar">
        <div className="sidebar-brand">
          <Link to="/" className="sidebar-logo">K</Link>
          <div className="sidebar-brand-text">
            <span className="sidebar-product">Kontexa</span>
            <span className="sidebar-role">Admin Console</span>
          </div>
        </div>

        <div className="sidebar-nav">
          {NAV_ITEMS.map((item) => (
            <button
              key={item.id}
              className={`sidebar-nav-btn ${activeSection === item.id ? 'active' : ''}`}
              onClick={() => setActiveSection(item.id)}
              type="button"
            >
              <span className="nav-icon">{item.icon}</span>
              <span className="nav-label">{item.label}</span>
            </button>
          ))}
        </div>

        <div className="sidebar-footer">
          <div className="sidebar-tenant-info" ref={profileMenuRef}>
            <button
              className="sidebar-profile-btn"
              onClick={() => setProfileOpen((v) => !v)}
              type="button"
            >
              <div className="sidebar-avatar">{tenantUserId[0]?.toUpperCase() || 'A'}</div>
              <div className="sidebar-profile-text">
                <span className="sidebar-profile-id">{tenantUserId || 'Admin'}</span>
                <span className="sidebar-profile-tenant">{tenantId}</span>
              </div>
            </button>
            {profileOpen && (
              <div className="profile-popover">
                <p className="profile-popover-title">Workspace Profile</p>
                <div className="profile-detail"><span>Tenant ID</span><strong>{tenantId || '—'}</strong></div>
                <div className="profile-detail"><span>User ID</span><strong>{tenantUserId || '—'}</strong></div>
                <div className="profile-detail">
                  <span>BigQuery</span>
                  <strong className={bigQueryConnected ? 'text-success' : 'text-muted'}>
                    {bigQueryConnected ? 'Connected' : 'Not connected'}
                  </strong>
                </div>
                <button className="profile-signout-btn" onClick={handleSignOut} type="button">
                  Sign Out
                </button>
              </div>
            )}
          </div>
        </div>
      </nav>

      {/* ── Main body ─────────────────────────────────────────────────── */}
      <main className="admin-body">
        <header className="admin-topbar">
          <div className="topbar-left">
            <h1 className="page-title">{current.title}</h1>
            <p className="page-sub">{current.sub}</p>
          </div>
          <div className="topbar-right">
            <div className={`conn-indicator ${bigQueryConnected ? 'connected' : ''}`}>
              <span className="conn-indicator-dot" />
              <span>{bigQueryConnected ? 'Connected' : 'No connection'}</span>
            </div>
          </div>
        </header>

        <div className="admin-content">
          {activeSection === 'overview'    && renderOverview()}
          {activeSection === 'connections' && renderConnections()}
          {activeSection === 'catalogues'  && renderCatalogues()}
          {activeSection === 'users'       && renderUsers()}
        </div>
      </main>
    </div>
  );
}

export default TenantDashboardPage;
