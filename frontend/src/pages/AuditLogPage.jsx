import { useState } from 'react';
import { reportingApi } from '../api/endpoints.js';
import { AuditDetails } from '../components/HistoryTimeline.jsx';
import { EmptyState, ErrorBanner, Pagination, Spinner } from '../components/ui.jsx';
import { useApi } from '../hooks/useApi.js';
import { formatDateTime, humanize } from '../utils/format.js';

const ENTITY_TYPES = ['', 'PURCHASE_REQUEST', 'QUOTATION', 'COMPARISON_REPORT', 'APPROVAL', 'VENDOR', 'CUSTOMER',
  'ENTITY', 'SUB_ENTITY', 'USER'];

export default function AuditLogPage() {
  const [filters, setFilters] = useState({ entityType: '', entityId: '', performedBy: '' });
  const [page, setPage] = useState(0);
  const params = {
    page,
    size: 50,
    entityType: filters.entityType || undefined,
    entityId: filters.entityId || undefined,
    performedBy: filters.performedBy || undefined,
  };
  const logs = useApi(() => reportingApi.auditLogs(params), [JSON.stringify(params)]);
  const set = (key) => (e) => { setFilters((f) => ({ ...f, [key]: e.target.value })); setPage(0); };

  return (
    <div className="page">
      <div className="page-header">
        <div>
          <h1>Audit log</h1>
          <p className="muted">History of important changes to quotations, comparisons, approvals and master data.</p>
        </div>
      </div>
      <div className="toolbar">
        <select aria-label="Record type" value={filters.entityType} onChange={set('entityType')}>
          {ENTITY_TYPES.map((t) => <option key={t} value={t}>{t ? humanize(t) : 'All record types'}</option>)}
        </select>
        <input type="number" min="1" placeholder="Record id" aria-label="Record id" value={filters.entityId} onChange={set('entityId')} />
        <input placeholder="User" aria-label="User" value={filters.performedBy} onChange={set('performedBy')} />
      </div>
      <ErrorBanner message={logs.error} onRetry={logs.reload} />
      {logs.loading && !logs.data ? <Spinner /> : logs.data?.content?.length ? (
        <div className="card card-flush">
          <table className="table table-compact">
            <thead><tr><th>When</th><th>User</th><th>Record</th><th>Action</th><th>Details</th></tr></thead>
            <tbody>
              {logs.data.content.map((log) => (
                <tr key={log.id}>
                  <td className="nowrap">{formatDateTime(log.performedAt)}</td>
                  <td>{log.performedBy}</td>
                  <td className="nowrap">{humanize(log.entityType)} #{log.entityId}</td>
                  <td><strong>{humanize(log.action)}</strong></td>
                  <td><AuditDetails json={log.details} /></td>
                </tr>
              ))}
            </tbody>
          </table>
          <Pagination page={page} totalPages={logs.data.totalPages} onChange={setPage} />
        </div>
      ) : <EmptyState title="No audit entries match these filters" />}
    </div>
  );
}
