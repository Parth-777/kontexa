import { useEffect, useMemo, useRef, useState } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { askQuestion, fetchTablesForSchema } from '../api/queryApi';
import './UserDashboardPage.css';

function UserDashboardPage() {
  const navigate = useNavigate();
  const session = JSON.parse(window.sessionStorage.getItem('kontexaUserSession') || '{}');
  const userId = session.userId || '';
  const tenantId = session.tenantId || '';
  const tenantSchema = session.tenantSchema || '';
  const position = session.position || '';
  const profileMenuRef = useRef(null);

  const [question, setQuestion] = useState('');
  const [activeMode, setActiveMode] = useState('table');
  const [availableTables, setAvailableTables] = useState([]);
  const [selectedTable, setSelectedTable] = useState('');
  const [result, setResult] = useState(null);
  const [columns, setColumns] = useState([]);
  const [errorMessage, setErrorMessage] = useState('');
  const [isRunning, setIsRunning] = useState(false);
  const [profileOpen, setProfileOpen] = useState(false);

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
    if (!tenantSchema) return;
    async function loadTables() {
      try {
        const payload = await fetchTablesForSchema(tenantSchema);
        const tables = payload.tables || [];
        setAvailableTables(tables);
        setSelectedTable(tables[0] || '');
      } catch (error) {
        setErrorMessage(error.message || 'Failed to load tables');
      }
    }
    loadTables();
  }, [tenantSchema]);

  const handleRunQuestion = async () => {
    const q = question.trim();
    if (!q || !tenantSchema) return;
    try {
      setIsRunning(true);
      setErrorMessage('');
      const payload = await askQuestion(tenantSchema, q);
      setResult(payload);

      const allKeys = new Set();
      (payload.rows || []).forEach((row) => {
        Object.keys(row || {}).forEach((key) => allKeys.add(key));
      });
      setColumns(Array.from(allKeys));
    } catch (error) {
      setErrorMessage(error.message || 'Failed to run query');
    } finally {
      setIsRunning(false);
    }
  };

  const chartRows = useMemo(() => (result?.rows || []).slice(0, 8), [result]);

  const handleSignOut = () => {
    window.sessionStorage.removeItem('kontexaUserSession');
    navigate('/signin');
  };

  return (
    <div className="user-dashboard-page">
      <div className="user-grid" />
      <div className="user-orb user-orb-left" />
      <div className="user-orb user-orb-right" />

      <header className="user-header">
        <div>
          <Link className="user-brand" to="/">
            KONTEXA
          </Link>
          <p className="user-header-subtitle">User workspace</p>
        </div>
        <div className="user-profile-menu" ref={profileMenuRef}>
          <button className="user-profile-trigger" onClick={() => setProfileOpen((v) => !v)} type="button">
            Profile
          </button>
          {profileOpen ? (
            <div className="user-profile-popover">
              <p className="user-profile-title">User Profile</p>
              <div className="user-profile-item">
                <span>User ID</span>
                <strong>{userId || 'Unknown'}</strong>
              </div>
              <div className="user-profile-item">
                <span>Tenant ID</span>
                <strong>{tenantId || 'Unknown'}</strong>
              </div>
              <div className="user-profile-item">
                <span>Tenant Schema</span>
                <strong>{tenantSchema || 'Unknown'}</strong>
              </div>
              <div className="user-profile-item">
                <span>Position</span>
                <strong>{position || 'Unknown'}</strong>
              </div>
              <button className="user-profile-signout" onClick={handleSignOut} type="button">
                Sign Out
              </button>
            </div>
          ) : null}
        </div>
      </header>

      <main className="user-main">
        <aside className="card user-sidebar">
          <h2>Your Database Tables</h2>
          <p>Ask questions for your assigned tenant and select the best table context.</p>
          <label htmlFor="tenant-schema">Tenant</label>
          <select id="tenant-schema" value={tenantSchema} disabled>
            <option value={tenantSchema}>{tenantSchema || 'N/A'}</option>
          </select>
          <div className="table-list-wrap">
            <h3>Tables ({availableTables.length})</h3>
            <ul className="table-list">
              {availableTables.map((table) => (
                <li key={table}>
                  <button
                    className={selectedTable === table ? 'active' : ''}
                    type="button"
                    onClick={() => setSelectedTable(table)}
                  >
                    {table}
                  </button>
                </li>
              ))}
            </ul>
          </div>
        </aside>

        <section className="card user-workspace">
          <div className="workspace-top">
            <div>
              <h1>User Dashboard</h1>
              <p>Ask questions, then switch between answer table, chart, and graph from the same response.</p>
            </div>
            <div className="mode-tabs">
              {['table', 'chart', 'graph'].map((mode) => (
                <button
                  key={mode}
                  className={activeMode === mode ? 'active' : ''}
                  onClick={() => setActiveMode(mode)}
                  type="button"
                  disabled={!result}
                >
                  {mode === 'table' ? 'Ask Questions' : mode === 'chart' ? 'Generate Chart' : 'Generate Graph'}
                </button>
              ))}
            </div>
          </div>

          <div className="question-card">
            <p className="table-context">
              Context table: <strong>{selectedTable || 'None'}</strong>
            </p>
            <textarea
              rows={3}
              value={question}
              onChange={(event) => setQuestion(event.target.value)}
              placeholder="Ask your data question"
            />
            <button type="button" onClick={handleRunQuestion} disabled={isRunning || !question.trim()}>
              {isRunning ? 'Running...' : 'Run Question'}
            </button>
          </div>

          {errorMessage ? <p className="workspace-error">{errorMessage}</p> : null}

          {result ? (
            <section className="result-panel">
              <div className="result-meta">
                <p>{result.rowCount} rows returned</p>
                <small>SQL generated for {tenantSchema}</small>
              </div>
              <pre>{result.generatedSql}</pre>

              {activeMode === 'table' ? (
                result.rowCount > 0 ? (
                  <div className="result-table-outer">
                    <table className="result-table">
                      <thead>
                        <tr>
                          {columns.map((column) => (
                            <th key={column}>{column}</th>
                          ))}
                        </tr>
                      </thead>
                      <tbody>
                        {result.rows.map((row, rowIndex) => (
                          <tr key={`${rowIndex}-${selectedTable}`}>
                            {columns.map((column) => (
                              <td key={column}>{row[column] ?? '—'}</td>
                            ))}
                          </tr>
                        ))}
                      </tbody>
                    </table>
                  </div>
                ) : (
                  <p className="empty-state">No rows returned for this query.</p>
                )
              ) : null}

              {activeMode === 'chart' ? (
                <div className="bar-chart">
                  {chartRows.map((row, index) => {
                    const key = columns[0];
                    const value = Number(row[columns[1]]) || 0;
                    const width = Math.max(8, Math.min(100, value || 12));
                    return (
                      <div className="bar-item" key={`${index}`}>
                        <span>{String(row[key] ?? `Row ${index + 1}`)}</span>
                        <div className="bar-track">
                          <div className="bar-fill" style={{ width: `${width}%` }} />
                        </div>
                        <strong>{value || '-'}</strong>
                      </div>
                    );
                  })}
                </div>
              ) : null}

              {activeMode === 'graph' ? (
                <svg className="line-graph" viewBox="0 0 780 240" preserveAspectRatio="none">
                  <polyline
                    fill="none"
                    stroke="#1de9d4"
                    strokeWidth="3"
                    points={chartRows
                      .map((row, index) => {
                        const value = Number(row[columns[1]]) || 0;
                        const x = chartRows.length <= 1 ? 0 : (index / (chartRows.length - 1)) * 760 + 10;
                        const y = 220 - Math.max(0, Math.min(200, value));
                        return `${x},${y}`;
                      })
                      .join(' ')}
                  />
                </svg>
              ) : null}
            </section>
          ) : null}
        </section>
      </main>
    </div>
  );
}

export default UserDashboardPage;
