import { useState } from 'react';
import { Link, useNavigate, useSearchParams } from 'react-router-dom';
import { purchaseRequestsApi } from '../api/endpoints.js';
import { useAuth } from '../auth/AuthContext.jsx';
import DropZone from '../components/DropZone.jsx';
import NewRfqModal from '../components/NewRfqModal.jsx';
import { useToast } from '../components/Toast.jsx';
import { EmptyState, ErrorBanner, Pagination, Spinner, StatusBadge } from '../components/ui.jsx';
import { useApi } from '../hooks/useApi.js';
import { formatDate, humanize } from '../utils/format.js';
import { can } from '../utils/permissions.js';

const STATUSES = ['', 'DRAFT', 'OPEN', 'UNDER_REVIEW', 'APPROVED', 'REJECTED', 'CANCELLED'];

export default function PurchaseRequestsPage() {
  const { user } = useAuth();
  const { notify } = useToast();
  const navigate = useNavigate();
  const [query, setQuery] = useState('');
  const [status, setStatus] = useState('');
  const [page, setPage] = useState(0);
  const [params, setParams] = useSearchParams();
  // { files } when an RFQ document was dropped on the page; {} for a blank window (also /purchase-requests?new=1)
  const [creating, setCreating] = useState(() => (params.get('new') && can.createRequest(user) ? {} : null));
  const list = useApi(
    () => purchaseRequestsApi.list({ q: query || undefined, status: status || undefined, page, size: 15 }),
    [query, status, page],
  );

  return (
    <div className="page">
      <div className="page-header">
        <div>
          <h1>RFQs</h1>
          <p className="muted">Requests for quotation: list the parts you need, open the RFQ to vendors, then compare their quotations side by side.</p>
        </div>
        {can.createRequest(user) && (
          <button type="button" className="btn btn-primary" onClick={() => setCreating({})}>New RFQ</button>
        )}
      </div>

      {can.createRequest(user) && (
        <DropZone onFiles={(files) => setCreating({ files })} title="Drop an RFQ document to create it"
                  hint="PDF, scanned PDF, Word or Excel. The title, required-by date and every item are filled in for you." />
      )}

      <div className="toolbar">
        <input type="search" className="search" placeholder="Search by title or number" aria-label="Search"
               value={query} onChange={(e) => { setQuery(e.target.value); setPage(0); }} />
        <select aria-label="Status" value={status} onChange={(e) => { setStatus(e.target.value); setPage(0); }}>
          {STATUSES.map((s) => <option key={s} value={s}>{s ? humanize(s) : 'All statuses'}</option>)}
        </select>
      </div>
      <ErrorBanner message={list.error} onRetry={list.reload} />

      {list.loading && !list.data ? <Spinner /> : list.data?.content?.length ? (
        <div className="card card-flush">
          <table className="table">
            <thead>
              <tr><th>Number</th><th>Title</th><th>Entity</th><th>Items</th><th>Requested by</th><th>Quotes due</th><th>Required by</th><th>Status</th></tr>
            </thead>
            <tbody>
              {list.data.content.map((pr) => (
                <tr key={pr.id} className="row-link" onClick={() => navigate(`/purchase-requests/${pr.id}`)}>
                  <td><Link to={`/purchase-requests/${pr.id}`} onClick={(e) => e.stopPropagation()}>{pr.requestNumber}</Link></td>
                  <td>{pr.title}</td>
                  <td>{pr.entityName}</td>
                  <td>{pr.itemCount}</td>
                  <td>{pr.requestedBy}</td>
                  <td>{formatDate(pr.quotationsDue)}</td>
                  <td>{formatDate(pr.requiredBy)}</td>
                  <td><StatusBadge status={pr.status} /></td>
                </tr>
              ))}
            </tbody>
          </table>
          <Pagination page={page} totalPages={list.data.totalPages} onChange={setPage} />
        </div>
      ) : <EmptyState title="No RFQs yet">Create an RFQ with the parts you need, then open it so vendors can quote.</EmptyState>}

      {creating && (
        <NewRfqModal initialFiles={creating.files} onClose={() => { setCreating(null); if (params.get('new')) setParams({}); }}
                     onCreated={(pr) => {
                       notify(`${pr.requestNumber} created with ${pr.items?.length ?? 0} item(s)`);
                       navigate(`/purchase-requests/${pr.id}`);
                     }} />
      )}
    </div>
  );
}
