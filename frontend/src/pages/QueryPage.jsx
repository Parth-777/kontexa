import { useState, useEffect } from "react";
import { fetchActiveClients, askQuestion } from "../api/queryApi";
import "./QueryPage.css";

export default function QueryPage() {
  const [clients, setClients]           = useState([]);
  const [selectedClient, setSelectedClient] = useState("");
  const [question, setQuestion]         = useState("");
  const [result, setResult]             = useState(null);
  const [loading, setLoading]           = useState(false);
  const [error, setError]               = useState(null);
  const [sqlExpanded, setSqlExpanded]   = useState(false);
  const [clientsLoading, setClientsLoading] = useState(true);

  useEffect(() => {
    fetchActiveClients()
      .then((data) => {
        setClients(data.clients || []);
        if (data.clients && data.clients.length > 0) {
          setSelectedClient(data.clients[0]);
        }
      })
      .catch(() => setError("Could not load clients. Is the backend running?"))
      .finally(() => setClientsLoading(false));
  }, []);

  async function handleAsk() {
    if (!question.trim() || !selectedClient) return;
    setLoading(true);
    setError(null);
    setResult(null);

    try {
      const data = await askQuestion(selectedClient, question);
      setResult(data);
    } catch (err) {
      setError(err.message);
    } finally {
      setLoading(false);
    }
  }

  function handleKeyDown(e) {
    if (e.key === "Enter" && !e.shiftKey) {
      e.preventDefault();
      handleAsk();
    }
  }

  function handleClientChange(clientId) {
    setSelectedClient(clientId);
    setResult(null);
    setError(null);
    setQuestion("");
  }

  const columns = result?.rows?.length > 0 ? Object.keys(result.rows[0]) : [];

  return (
    <div className="query-page">

      {/* Header */}
      <div className="query-header">
        <h1>Ask a Question</h1>
        <p>Select a client and type any question in plain English</p>
      </div>

      {/* Client Selector */}
      <div className="client-selector">
        <span className="client-label">Client</span>
        {clientsLoading ? (
          <span className="client-loading">Loading clients...</span>
        ) : (
          <div className="client-tabs">
            {clients.map((c) => (
              <button
                key={c}
                className={`client-tab ${selectedClient === c ? "active" : ""}`}
                onClick={() => handleClientChange(c)}
              >
                {c}
              </button>
            ))}
          </div>
        )}
      </div>

      {/* Input Area */}
      <div className="query-input-area">
        <textarea
          className="query-input"
          placeholder={
            selectedClient
              ? `Ask anything about "${selectedClient}" data...`
              : "Select a client first"
          }
          value={question}
          onChange={(e) => setQuestion(e.target.value)}
          onKeyDown={handleKeyDown}
          rows={3}
          disabled={loading || !selectedClient}
        />
        <button
          className={`query-button ${loading ? "loading" : ""}`}
          onClick={handleAsk}
          disabled={loading || !question.trim() || !selectedClient}
        >
          {loading ? (
            <span className="spinner-wrapper">
              <span className="spinner" /> Thinking...
            </span>
          ) : (
            "Ask"
          )}
        </button>
      </div>

      {/* Error */}
      {error && (
        <div className="query-error">
          <span>⚠ {error}</span>
        </div>
      )}

      {/* Results */}
      {result && (
        <div className="query-results">

          {/* SQL toggle */}
          <div className="sql-section">
            <button
              className="sql-toggle"
              onClick={() => setSqlExpanded(!sqlExpanded)}
            >
              {sqlExpanded ? "▾" : "▸"} Generated SQL
            </button>
            {sqlExpanded && (
              <pre className="sql-code">{result.generatedSql}</pre>
            )}
          </div>

          {/* Row count */}
          <div className="result-meta">
            {result.rowCount === 0
              ? "No results found"
              : `${result.rowCount} row${result.rowCount !== 1 ? "s" : ""} returned`}
          </div>

          {/* Table */}
          {result.rowCount > 0 && (
            <div className="table-wrapper">
              <table className="result-table">
                <thead>
                  <tr>
                    {columns.map((col) => (
                      <th key={col}>{col}</th>
                    ))}
                  </tr>
                </thead>
                <tbody>
                  {result.rows.map((row, i) => (
                    <tr key={i}>
                      {columns.map((col) => (
                        <td key={col}>
                          {row[col] !== null && row[col] !== undefined
                            ? String(row[col])
                            : "—"}
                        </td>
                      ))}
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
        </div>
      )}
    </div>
  );
}