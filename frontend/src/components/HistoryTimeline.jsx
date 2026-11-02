import { useApi } from '../hooks/useApi.js';
import { formatDateTime, humanize } from '../utils/format.js';
import { EmptyState, ErrorBanner, Spinner } from './ui.jsx';

/**
 * Change history of one record, newest first, from the audit trail.
 * `load` returns a page of audit entries; `showRecord` adds a column naming the record each entry belongs to.
 */
export default function HistoryTimeline({ load, deps = [], showRecord = false, title = 'Change history' }) {
  const history = useApi(load, deps);
  const entries = history.data?.content || [];

  return (
    <section className="card card-flush">
      <div className="card-header card-header-padded">
        <div>
          <h2>{title}</h2>
          <p className="muted small">Who did what and when. Entries cannot be edited or deleted.</p>
        </div>
        <button type="button" className="btn btn-small" onClick={() => history.reload()} disabled={history.loading}>Refresh</button>
      </div>
      <ErrorBanner message={history.error} onRetry={history.reload} />
      {history.loading && !history.data ? <Spinner /> : entries.length ? (
        <div className="table-scroll">
          <table className="table table-compact">
            <thead>
              <tr><th>When</th><th>User</th>{showRecord && <th>Record</th>}<th>Action</th><th>Details</th></tr>
            </thead>
            <tbody>
              {entries.map((log) => (
                <tr key={log.id}>
                  <td className="nowrap">{formatDateTime(log.performedAt)}</td>
                  <td>{log.performedBy}</td>
                  {showRecord && <td className="nowrap">{humanize(log.entityType)} #{log.entityId}</td>}
                  <td><strong>{humanize(log.action)}</strong></td>
                  <td><AuditDetails json={log.details} /></td>
                </tr>
              ))}
            </tbody>
          </table>
          {history.data.totalElements > entries.length && (
            <p className="muted small history-more">Showing the latest {entries.length} of {history.data.totalElements} entries. The full trail is in the audit log.</p>
          )}
        </div>
      ) : <EmptyState title="No history yet" />}
    </section>
  );
}

/** Renders the JSON details of an audit entry as a compact key/value list. */
export function AuditDetails({ json }) {
  if (!json) return <span className="muted">—</span>;
  let parsed;
  try {
    parsed = JSON.parse(json);
  } catch {
    return <span>{json}</span>;
  }
  return (
    <dl className="details">
      {Object.entries(parsed).map(([key, value]) => (
        <div key={key}>
          <dt>{humanize(key.replace(/([A-Z])/g, '_$1'))}</dt>
          <dd>{typeof value === 'object' && value !== null ? JSON.stringify(value) : String(value ?? '—')}</dd>
        </div>
      ))}
    </dl>
  );
}
