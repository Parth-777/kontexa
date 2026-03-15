import { useEffect, useMemo, useRef, useState } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import {
  createTenantUser,
  deleteTenantUser,
  fetchTenantCloudLink,
  fetchTenantUsers,
  updateTenantCloudLink,
  updateTenantUserRole,
  updateTenantUserStatus,
} from '../api/authApi';
import './TenantDashboardPage.css';

const roles = ['Admin', 'Manager', 'Analyst', 'Viewer'];

function TenantDashboardPage() {
  const navigate = useNavigate();
  const tenantSession = JSON.parse(window.sessionStorage.getItem('kontexaTenantSession') || '{}');
  const tenantId = tenantSession.tenantId || '';
  const tenantUserId = tenantSession.userId || '';
  const profileMenuRef = useRef(null);

  const [users, setUsers] = useState([]);
  const [activeFilter, setActiveFilter] = useState('all');
  const [searchText, setSearchText] = useState('');
  const [newUser, setNewUser] = useState({ id: '', password: '', role: 'Viewer', isActive: true });
  const [loadingUsers, setLoadingUsers] = useState(true);
  const [savingUser, setSavingUser] = useState(false);
  const [savingCloudLink, setSavingCloudLink] = useState(false);
  const [profileOpen, setProfileOpen] = useState(false);
  const [cloudDbLink, setCloudDbLink] = useState(tenantSession.cloudDbLink || '');
  const [cloudLinkFeedback, setCloudLinkFeedback] = useState('');
  const [feedback, setFeedback] = useState('');
  const [errorMessage, setErrorMessage] = useState('');

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
      setErrorMessage('');
      try {
        const [usersPayload, linkPayload] = await Promise.all([
          fetchTenantUsers(tenantId),
          fetchTenantCloudLink(tenantId).catch(() => ({ cloudDbLink: '' })),
        ]);
        setUsers(
          (usersPayload.users || []).map((user) => ({
            id: user.user_id || user.userId,
            role: user.position || 'Viewer',
            isActive: Boolean(user.active),
          }))
        );
        setCloudDbLink(linkPayload.cloudDbLink || '');
      } catch (error) {
        setErrorMessage(error.message || 'Failed to load tenant dashboard');
      } finally {
        setLoadingUsers(false);
      }
    }
    loadData();
  }, [tenantId]);

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

  const handleSaveCloudLink = async (event) => {
    event.preventDefault();
    if (!tenantId || !cloudDbLink.trim()) return;
    try {
      setSavingCloudLink(true);
      await updateTenantCloudLink({ tenantId, cloudDbLink: cloudDbLink.trim() });
      const updated = { ...tenantSession, cloudDbLink: cloudDbLink.trim() };
      window.sessionStorage.setItem('kontexaTenantSession', JSON.stringify(updated));
      setCloudLinkFeedback('Cloud database link saved successfully.');
    } catch (error) {
      setCloudLinkFeedback(error.message || 'Failed to save cloud database link');
    } finally {
      setSavingCloudLink(false);
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
                <span>Cloud DB Link</span>
                <strong>{cloudDbLink || 'Not set'}</strong>
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
            <article className="tenant-card cloud-link-card">
              <h2>Cloud Database Access</h2>
              <form onSubmit={handleSaveCloudLink}>
                <label htmlFor="cloud-link">Database link</label>
                <input
                  id="cloud-link"
                  type="text"
                  value={cloudDbLink}
                  onChange={(event) => setCloudDbLink(event.target.value)}
                />
                <button type="submit" disabled={savingCloudLink}>
                  {savingCloudLink ? 'Saving...' : 'Save Link'}
                </button>
              </form>
              {cloudLinkFeedback ? <p className="tenant-feedback">{cloudLinkFeedback}</p> : null}
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
