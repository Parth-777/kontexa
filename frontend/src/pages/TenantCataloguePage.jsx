import { useEffect, useMemo, useState } from 'react';
import { Link, useNavigate, useParams } from 'react-router-dom';
import { getCataloguePreview, updateCatalogue } from '../api/catalogueApi';
import { getSession } from '../api/session';
import './TenantCataloguePage.css';

function isPresent(value) {
  if (value == null) return false;
  if (typeof value === 'string') {
    const trimmed = value.trim();
    return trimmed.length > 0 && trimmed !== '[]' && trimmed !== '{}';
  }
  if (Array.isArray(value)) return value.length > 0;
  if (typeof value === 'object') return Object.keys(value).length > 0;
  return true;
}

function parseMaybeJson(value) {
  if (value == null) return null;
  if (typeof value === 'object') return value;
  if (typeof value !== 'string') return value;
  const trimmed = value.trim();
  if (!trimmed) return null;
  try {
    return JSON.parse(trimmed);
  } catch {
    return value;
  }
}

function asList(value) {
  const parsed = parseMaybeJson(value);
  if (Array.isArray(parsed)) {
    return parsed.filter((item) => item != null && String(item).trim() !== '');
  }
  return [];
}

function asRecord(value) {
  const parsed = parseMaybeJson(value);
  if (parsed && typeof parsed === 'object' && !Array.isArray(parsed)) return parsed;
  return null;
}

function TenantCataloguePage() {
  const { catalogueId } = useParams();
  const navigate = useNavigate();
  const tenantSession = getSession() || {};
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

  const entities = useMemo(() => asList(draftCatalogue?.entities), [draftCatalogue]);
  const caveats = useMemo(() => asList(draftCatalogue?.globalCaveats), [draftCatalogue]);

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
                Client <strong>{draftCatalogue.clientId}</strong>
                {isPresent(draftCatalogue.databaseName) ? (
                  <>
                    {' '}
                    | Database <strong>{draftCatalogue.databaseName}</strong>
                  </>
                ) : null}{' '}
                | Schema <strong>{draftCatalogue.schemaName}</strong> | Status{' '}
                <strong>{draftCatalogue.status}</strong>
              </p>
              <p>
                {draftCatalogue.tableCount} tables | {draftCatalogue.columnCount} columns
              </p>
              <div className="catalogue-lifecycle">
                {isPresent(draftCatalogue.status) ? (
                  <span className="lifecycle-chip">Status: {draftCatalogue.status}</span>
                ) : null}
                {isPresent(draftCatalogue.lineageKey) ? (
                  <span className="lifecycle-chip">Lineage: {draftCatalogue.lineageKey}</span>
                ) : null}
                {isPresent(draftCatalogue.supersedesCatalogueId) ? (
                  <span className="lifecycle-chip">
                    Supersedes #{draftCatalogue.supersedesCatalogueId}
                  </span>
                ) : null}
                {isPresent(draftCatalogue.createdAt) ? (
                  <span className="lifecycle-chip">Created: {draftCatalogue.createdAt}</span>
                ) : null}
                {isPresent(draftCatalogue.updatedAt) ? (
                  <span className="lifecycle-chip">Updated: {draftCatalogue.updatedAt}</span>
                ) : null}
              </div>
              {isPresent(draftCatalogue.businessProfile) ? (
                <div className="catalogue-semantic-block">
                  <h3>Business profile</h3>
                  <p>{draftCatalogue.businessProfile}</p>
                </div>
              ) : null}
              {entities.length > 0 ? (
                <div className="catalogue-semantic-block">
                  <h3>Entities</h3>
                  <ul className="catalogue-entity-list">
                    {entities.map((entity, index) => {
                      const name = typeof entity === 'string' ? entity : entity?.name;
                      const description = typeof entity === 'object' ? entity?.description : '';
                      const grain = typeof entity === 'object' ? entity?.grain : '';
                      return (
                        <li key={`${name || index}`}>
                          <strong>{name || `Entity ${index + 1}`}</strong>
                          {isPresent(grain) ? <span> · grain: {grain}</span> : null}
                          {isPresent(description) ? <p>{description}</p> : null}
                        </li>
                      );
                    })}
                  </ul>
                </div>
              ) : null}
              {caveats.length > 0 ? (
                <div className="catalogue-semantic-block">
                  <h3>Global caveats</h3>
                  <ul>
                    {caveats.map((caveat, index) => (
                      <li key={`${index}-${String(caveat).slice(0, 24)}`}>
                        {typeof caveat === 'string' ? caveat : JSON.stringify(caveat)}
                      </li>
                    ))}
                  </ul>
                </div>
              ) : null}
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
                  {isPresent(table.tableRole) ? (
                    <p className="table-role-label">Table role: {table.tableRole}</p>
                  ) : null}
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
                        <div className="column-structure">
                          <span className="meta-label">Structure</span>
                          <strong>{column.columnName}</strong>
                          <small>{column.dataType || 'type unknown'}</small>
                        </div>
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
                          <div className="column-semantics">
                            <span className="meta-label">Meaning</span>
                            {isPresent(column.role) ? (
                              <em className="column-role">Role: {column.role}</em>
                            ) : null}
                            {isPresent(column.description) ? <p>{column.description}</p> : null}
                            {isPresent(column.businessMeaning) ? (
                              <p className="column-business-meaning">{column.businessMeaning}</p>
                            ) : null}
                            {isPresent(column.aggregationMethod) ? (
                              <p className="column-meta">Aggregation: {column.aggregationMethod}</p>
                            ) : null}
                            {asList(column.synonyms).length > 0 ? (
                              <p className="column-meta">
                                Synonyms: {asList(column.synonyms).join(', ')}
                              </p>
                            ) : null}
                            {asRecord(column.valueMeanings)
                              && Object.keys(asRecord(column.valueMeanings)).length > 0 ? (
                              <p className="column-meta">
                                Value meanings:{' '}
                                {Object.entries(asRecord(column.valueMeanings))
                                  .map(([key, val]) => `${key}=${val}`)
                                  .join(', ')}
                              </p>
                            ) : null}
                            {column.confidence != null && column.confidence !== '' ? (
                              <p className="column-meta">Confidence: {column.confidence}</p>
                            ) : null}
                            {isPresent(column.displayUnit) ? (
                              <p className="column-meta">Display unit: {column.displayUnit}</p>
                            ) : null}
                            {column.scaleToCanonical != null && column.scaleToCanonical !== '' ? (
                              <p className="column-meta">Scale to canonical: {column.scaleToCanonical}</p>
                            ) : null}
                            {isPresent(column.grainLabel) ? (
                              <p className="column-meta">Grain: {column.grainLabel}</p>
                            ) : null}
                          </div>
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
