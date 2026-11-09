import { useState } from 'react';
import { Link } from 'react-router-dom';
import { purchaseRequestsApi } from '../api/endpoints.js';
import { EmptyState, ErrorBanner, Pagination, Spinner, StatusBadge, Tabs } from '../components/ui.jsx';
import { useApi } from '../hooks/useApi.js';
import { formatDate } from '../utils/format.js';

const VIEWS = [
  { id: 'UNDER_REVIEW', label: 'Awaiting award' },
  { id: 'APPROVED', label: 'Awarded' },
  { id: 'REJECTED', label: 'Rejected' },
];

/**
 * Read-only register of RFQs that reached the award stage: those being compared, awarded or rejected. It is the
 * buyers' and managers' view; opening one shows its comparison, decision and full history.
 */
export default function AwardRegisterPage() {
  const [status, setStatus] = useState('UNDER_REVIEW');
  const [page, setPage] = useState(0);
  const list = useApi(() => purchaseRequestsApi.list({ status, page, size: 20 }), [status, page]);

  return (
    <div className="page">
      <div className="page-header">
        <div>
          <h1>Award register</h1>
          <p className="muted">RFQs at the award stage. Open one to see its comparison, the decision, who made it and why.</p>
        </div>
      </div>
      <Tabs active={status} onChange={(s) => { setStatus(s); setPage(0); }} tabs={VIEWS} />
      <section className="card card-flush">
        <ErrorBanner message={list.error} onRetry={list.reload} />
        {list.loading && !list.data ? <Spinner /> : list.data?.content?.length ? (
          <>
            <table className="table">
              <thead><tr><th>RFQ</th><th>Title</th><th>Company</th><th>Status</th><th>Raised by</th><th>Created</th><th /></tr></thead>
              <tbody>
                {list.data.content.map((pr) => (
                  <tr key={pr.id}>
                    <td><Link to={`/purchase-requests/${pr.id}`}>{pr.requestNumber}</Link></td>
                    <td>{pr.title}</td>
                    <td>{pr.entityName}</td>
                    <td><StatusBadge status={pr.status} /></td>
                    <td>{pr.requestedBy}</td>
                    <td>{formatDate(pr.createdAt)}</td>
                    <td className="actions-col">
                      {['APPROVED', 'REJECTED'].includes(pr.status) && (
                        <button type="button" className="btn btn-small"
                                onClick={() => purchaseRequestsApi.downloadAwardSummary(pr.id, pr.requestNumber)}>PDF</button>
                      )}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
            <Pagination page={page} totalPages={list.data.totalPages} onChange={setPage} />
          </>
        ) : <EmptyState title="Nothing here yet">RFQs appear once their quotations have been compared.</EmptyState>}
      </section>
    </div>
  );
}
