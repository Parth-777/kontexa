import { useEffect, useMemo, useState } from 'react';
import { Link, useNavigate, useParams } from 'react-router-dom';
import { getCataloguePreview, updateCatalogue } from '../api/catalogueApi';
import './TenantCataloguePage.css';

function TenantCataloguePage() {
  const { catalogueId } = useParams();
  const navigate = useNavigate();
  const tenantSession = JSON.parse(window.sessionStorage.getItem('kontexaTenantSession') || '{}');
  const tenantId = tenantSession.tenantId || '';
  const tenantSchema = tenantSession.tenantSchema || '';

  const [catalogue, setCatalogue] = useState(null);
  const [draftCatalogue, setDraftCatalogue] = useState(null);
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);
  const [isEditMode, setIsEditMode] = useState(false);
  const [errorMessage, setErrorMessage] = useState('');
  const [feedback, setFeedback] = useState('');
  const [query, setQuery] = useState('');

  useEffect(() => {
    async function loadCatalogue() {
      setLoading(true);
      setErrorMessage('');
      try {
        const payload = await getCataloguePreview(catalogueId, { tenantId, tenantSchema });
        setCatalogue(payload);
        setDraftCatalogue(payload);
      } catch (error) {
        setErrorMessage(error.message || 'Failed to load catalogue');
      } finally {
        setLoading(false);
      }
    }
    if (catalogueId) {
      loadCatalogue();
    }
  }, [catalogueId, tenantId, tenantSchema]);

  const filteredTables = useMemo(() => {
    if (!draftCatalogue?.tables) return [];
    const q = query.trim().toLowerCase();
    if (!q) return draftCatalogue.tables;
    return draftCatalogue.tables.filter((table) => {
      const tableName = `${table.tableSchema}.${table.tableName}`.toLowerCase();
      if (tableName.includes(q)) return true;
      return (table.columns || []).some((col) => String(col.columnName || '').toLowerCase().includes(q));
    });
  }, [draftCatalogue, query]);

  const handleTableDescriptionChange = (tableKey, value) => {
    setDraftCatalogue((curr) => {
      if (!curr) return curr;
      return {
        ...curr,
        tables: (curr.tables || []).map((t) =>
          `${t.tableSchema}.${t.tableName}` === tableKey ? { ...t, description: value } : t
        ),
      };
    });
  };

  const handleColumnChange = (tableKey, columnName, field, value) => {
    setDraftCatalogue((curr) => {
      if (!curr) return curr;
      return {
        ...curr,
        tables: (curr.tables || []).map((t) => {
          if (`${t.tableSchema}.${t.tableName}` !== tableKey) return t;
          return {
            ...t,
            columns: (t.columns || []).map((c) =>
              c.columnName === columnName ? { ...c, [field]: value } : c
            ),
          };
        }),
      };
    });
  };

  const handleSaveEdits = async () => {
    if (!draftCatalogue) return;
    setSaving(true);
    setErrorMessage('');
    setFeedback('');
    try {
      const tablesPayload = (draftCatalogue.tables || []).map((t) => ({
        tableName: t.tableName,
        tableSchema: t.tableSchema,
        description: t.description || '',
        columns: (t.columns || []).map((c) => ({
          columnName: c.columnName,
          description: c.description || '',
          role: c.role || '',
        })),
      }));
      await updateCatalogue(catalogueId, {
        tenantId,
        tenantSchema,
        clientId: draftCatalogue.clientId,
        tables: tablesPayload,
      });
      setCatalogue(draftCatalogue);
      setIsEditMode(false);
      setFeedback('Catalogue changes saved successfully.');
    } catch (error) {
      setErrorMessage(error.message || 'Failed to save catalogue edits');
    } finally {
      setSaving(false);
    }
  };

  return (
    <div className="tenant-catalogue-page">
      <header className="tenant-catalogue-header">
        <div>
          <Link className="tenant-catalogue-brand" to="/">
            KONTEXA
          </Link>
          <p>Catalogue Viewer</p>
        </div>
        <button type="button" onClick={() => navigate('/tenant/dashboard')}>
          Back to Dashboard
        </button>
      </header>

      <main className="tenant-catalogue-main">
        {loading ? <p>Loading catalogue...</p> : null}
        {errorMessage ? <p className="tenant-catalogue-error">{errorMessage}</p> : null}
        {!loading && !errorMessage && draftCatalogue ? (
          <>
            <section className="tenant-catalogue-summary">
              <div className="tenant-catalogue-summary-head">
                <h1>Catalogue #{draftCatalogue.id}</h1>
                <div className="tenant-catalogue-actions">
                  {isEditMode ? (
                    <>
                      <button type="button" onClick={handleSaveEdits} disabled={saving}>
                        {saving ? 'Saving...' : 'Save'}
                      </button>
                      <button
                        type="button"
                        onClick={() => {
                          setDraftCatalogue(catalogue);
                          setIsEditMode(false);
                        }}
                        disabled={saving}
                      >
                        Cancel
                      </button>
                    </>
                  ) : (
                    <button type="button" onClick={() => setIsEditMode(true)}>
                      Edit
                    </button>
                  )}
                </div>
              </div>
              <p>
                Client <strong>{draftCatalogue.clientId}</strong> | Schema <strong>{draftCatalogue.schemaName}</strong>{' '}
                | Status <strong>{draftCatalogue.status}</strong>
              </p>
              <p>
                {draftCatalogue.tableCount} tables | {draftCatalogue.columnCount} columns
              </p>
              <input
                type="text"
                placeholder="Search table or column"
                value={query}
                onChange={(event) => setQuery(event.target.value)}
              />
              {feedback ? <p className="tenant-catalogue-feedback">{feedback}</p> : null}
            </section>

            <section className="tenant-catalogue-tables">
              {filteredTables.map((table) => (
                <article key={`${table.tableSchema}.${table.tableName}`} className="tenant-catalogue-table-card">
                  <div className="table-head">
                    <h2>
                      {table.tableSchema}.{table.tableName}
                    </h2>
                    <span>{table.columnCount} columns</span>
                  </div>
                  {isEditMode ? (
                    <textarea
                      className="table-desc-input"
                      rows={2}
                      value={table.description || ''}
                      onChange={(event) =>
                        handleTableDescriptionChange(
                          `${table.tableSchema}.${table.tableName}`,
                          event.target.value
                        )
                      }
                    />
                  ) : (
                    <p>{table.description || 'No description available.'}</p>
                  )}
                  <div className="column-list">
                    {(table.columns || []).map((column) => (
                      <div key={column.columnName} className="column-item">
                        <strong>{column.columnName}</strong>
                        <small>{column.dataType || 'type unknown'}</small>
                        {isEditMode ? (
                          <>
                            <input
                              className="column-role-input"
                              type="text"
                              value={column.role || ''}
                              onChange={(event) =>
                                handleColumnChange(
                                  `${table.tableSchema}.${table.tableName}`,
                                  column.columnName,
                                  'role',
                                  event.target.value
                                )
                              }
                              placeholder="Role (dimension/metric/filter...)"
                            />
                            <textarea
                              rows={2}
                              value={column.description || ''}
                              onChange={(event) =>
                                handleColumnChange(
                                  `${table.tableSchema}.${table.tableName}`,
                                  column.columnName,
                                  'description',
                                  event.target.value
                                )
                              }
                              placeholder="Column description"
                            />
                          </>
                        ) : (
                          <>
                            {column.role ? <em className="column-role">Role: {column.role}</em> : null}
                            {column.description ? <p>{column.description}</p> : null}
                          </>
                        )}
                      </div>
                    ))}
                  </div>
                </article>
              ))}
              {filteredTables.length === 0 ? <p>No table/column matches your search.</p> : null}
            </section>
          </>
        ) : null}
      </main>
    </div>
  );
}

export default TenantCataloguePage;
