import { useEffect, useState } from 'react';
import { Link, useNavigate, useParams } from 'react-router-dom';
import { errorMessage, fieldErrors } from '../api/client.js';
import { purchaseRequestsApi, vendorsApi } from '../api/endpoints.js';
import { useAuth } from '../auth/AuthContext.jsx';
import ApprovalPanel from '../components/ApprovalPanel.jsx';
import ComparisonMatrix from '../components/ComparisonMatrix.jsx';
import EnginePicker, { parseChoice } from '../components/EnginePicker.jsx';
import FilePicker from '../components/FilePicker.jsx';
import HistoryTimeline from '../components/HistoryTimeline.jsx';
import PurchaseRequestForm from '../components/PurchaseRequestForm.jsx';
import { useToast } from '../components/Toast.jsx';
import {
  ConfirmDialog, EmptyState, ErrorBanner, Field, Modal, Spinner, StatusBadge, Tabs,
} from '../components/ui.jsx';
import { useApi } from '../hooks/useApi.js';
import {
  emptyToNull, formatConfidence, formatDate, formatDateTime, formatMoney, formatNumber,
} from '../utils/format.js';
import { comparisonToCsv, downloadText } from '../utils/comparisonCsv.js';
import { can } from '../utils/permissions.js';

const IN_PROGRESS = ['UPLOADED', 'PROCESSING'];

export default function PurchaseRequestDetailPage() {
  const { id } = useParams();
  const { user } = useAuth();
  const { notify } = useToast();
  const navigate = useNavigate();
  const [tab, setTab] = useState('items');
  const [editing, setEditing] = useState(false);
  const [confirm, setConfirm] = useState(null);

  const pr = useApi(() => purchaseRequestsApi.get(id), [id]);
  const quotations = useApi(() => purchaseRequestsApi.quotations(id), [id]);
  const comparisons = useApi(() => purchaseRequestsApi.comparisons(id), [id]);
  const approvals = useApi(() => purchaseRequestsApi.approvals(id), [id]);

  // Extraction runs in the background: refresh while any quotation is still being processed.
  const processing = (quotations.data || []).some((q) => IN_PROGRESS.includes(q.status));
  useEffect(() => {
    if (!processing) return undefined;
    const timer = setInterval(() => quotations.reload({ silent: true }), 2500);
    return () => clearInterval(timer);
  }, [processing, quotations.reload]);

  if (pr.loading && !pr.data) return <Spinner label="Loading RFQ" />;
  if (pr.error && !pr.data) return <div className="page"><ErrorBanner message={pr.error} onRetry={pr.reload} /></div>;
  const request = pr.data;
  const editable = can.editRequest(user, request) && request.editable;
  const refreshAll = () => { pr.reload({ silent: true }); quotations.reload({ silent: true }); comparisons.reload({ silent: true }); approvals.reload({ silent: true }); };

  async function changeStatus(status) {
    try {
      pr.setData(await purchaseRequestsApi.changeStatus(id, status));
      notify(`Request is now ${status.toLowerCase().replace('_', ' ')}`);
    } catch (err) {
      notify(errorMessage(err), 'error');
    } finally {
      setConfirm(null);
    }
  }

  async function deleteRequest() {
    try {
      await purchaseRequestsApi.remove(id);
      notify('Request deleted');
      navigate('/purchase-requests');
    } catch (err) {
      notify(errorMessage(err), 'error');
      setConfirm(null);
    }
  }

  const latest = comparisons.data?.[0];
  return (
    <div className="page">
      <nav className="breadcrumbs"><Link to="/purchase-requests">RFQs</Link> / {request.requestNumber}</nav>
      <div className="page-header">
        <div>
          <h1>{request.title} <StatusBadge status={request.status} /></h1>
          <p className="muted">
            {request.requestNumber} · {request.entityName}{request.subEntityName ? ` / ${request.subEntityName}` : ''}
            {request.customerName ? ` · for ${request.customerName}` : ''} · requested by {request.requestedBy}
            {request.requiredBy ? ` · needed by ${formatDate(request.requiredBy)}` : ''} · {request.currency}
          </p>
          {request.description && <p>{request.description}</p>}
        </div>
        {can.editRequest(user, request) && (
          <div className="btn-row">
            {editable && <button type="button" className="btn" onClick={() => setEditing(true)}>Edit details</button>}
            {request.status === 'DRAFT' && (
              <button type="button" className="btn btn-primary" onClick={() => changeStatus('OPEN')}
                      disabled={!request.items.length} title={request.items.length ? '' : 'Add items first'}>
                Open to vendors
              </button>
            )}
            {request.status === 'OPEN' && !quotations.data?.length && (
              <button type="button" className="btn" onClick={() => changeStatus('DRAFT')}>Back to draft</button>
            )}
            {['DRAFT', 'OPEN', 'UNDER_REVIEW'].includes(request.status) && (
              <button type="button" className="btn btn-danger-text" onClick={() => setConfirm('cancel')}>Cancel request</button>
            )}
            {request.status === 'DRAFT' && (
              <button type="button" className="btn btn-danger-text" onClick={() => setConfirm('delete')}>Delete</button>
            )}
          </div>
        )}
      </div>

      <Steps request={request} quotations={quotations.data || []} comparisons={comparisons.data || []} />

      <Tabs active={tab} onChange={setTab} tabs={[
        { id: 'items', label: 'Requested items', count: request.items.length },
        { id: 'quotations', label: 'Quotations', count: quotations.data?.length ?? 0 },
        { id: 'comparison', label: 'Comparison', count: comparisons.data?.length ?? 0 },
        { id: 'approval', label: 'Award', count: approvals.data?.length ?? 0 },
        { id: 'history', label: 'History' },
      ]} />

      {tab === 'items' && <ItemsSection request={request} editable={editable} onChange={(updated) => { pr.setData(updated); quotations.reload({ silent: true }); }} />}
      {tab === 'quotations' && (
        <QuotationsSection request={request} quotations={quotations} canUpload={editable && ['OPEN', 'UNDER_REVIEW'].includes(request.status)}
                           onUploaded={() => quotations.reload({ silent: true })} />
      )}
      {tab === 'comparison' && (
        <ComparisonSection request={request} comparisons={comparisons} quotations={quotations.data || []}
                           canGenerate={can.editRequest(user, request) && ['OPEN', 'UNDER_REVIEW'].includes(request.status)}
                           onGenerated={() => { refreshAll(); }} />
      )}
      {tab === 'approval' && (
        <ApprovalPanel request={request} latest={latest} approvals={approvals.data || []}
                       canDecide={can.approve(user, request)} onDecided={() => refreshAll()} />
      )}
      {tab === 'history' && (
        <HistoryTimeline showRecord load={() => purchaseRequestsApi.history(request.id)}
                         deps={[request.id, request.status, quotations.data, comparisons.data, approvals.data]} />
      )}

      {editing && (
        <PurchaseRequestForm request={request} onClose={() => setEditing(false)}
                             onSaved={(saved) => { pr.setData(saved); setEditing(false); notify('Request updated'); }} />
      )}
      {confirm === 'cancel' && (
        <ConfirmDialog title="Cancel request" danger confirmLabel="Cancel request" onCancel={() => setConfirm(null)}
                       onConfirm={() => changeStatus('CANCELLED')}
                       message="Cancelling closes the request. Quotations and comparisons are kept for the record." />
      )}
      {confirm === 'delete' && (
        <ConfirmDialog title="Delete request" danger confirmLabel="Delete" onCancel={() => setConfirm(null)}
                       onConfirm={deleteRequest} message={`Delete draft ${request.requestNumber}? This cannot be undone.`} />
      )}
    </div>
  );
}

/** The workflow at a glance: items -> vendor quotations -> review -> comparison -> award. */
function Steps({ request, quotations, comparisons }) {
  const ready = quotations.filter((q) => q.status === 'READY_FOR_COMPARISON').length;
  const steps = [
    { label: 'Items added', done: request.items.length > 0, detail: `${request.items.length} item(s)` },
    { label: 'Quotations received', done: quotations.length > 0, detail: `${quotations.length} received` },
    { label: 'Reviewed', done: ready > 0, detail: `${ready} ready` },
    { label: 'Compared', done: comparisons.length > 0, detail: comparisons.length ? `v${comparisons[0].reportVersion}` : 'not yet' },
    { label: 'Awarded', done: ['APPROVED', 'REJECTED'].includes(request.status), detail: request.status === 'APPROVED' ? 'awarded' : request.status === 'REJECTED' ? 'rejected' : 'pending' },
  ];
  return (
    <ol className="steps">
      {steps.map((s, i) => (
        <li key={s.label} className={s.done ? 'step step-done' : 'step'}>
          <span className="step-number">{s.done ? '✓' : i + 1}</span>
          <span><strong>{s.label}</strong><br /><span className="muted small">{s.detail}</span></span>
        </li>
      ))}
    </ol>
  );
}

// ------------------------------------------------------------------ items

function ItemsSection({ request, editable, onChange }) {
  const { notify } = useToast();
  const [editing, setEditing] = useState(null);
  const [deleting, setDeleting] = useState(null);
  const itemsLocked = !['DRAFT', 'OPEN'].includes(request.status);

  async function remove() {
    try {
      onChange(await purchaseRequestsApi.removeItem(request.id, deleting.id));
      notify('Item removed');
    } catch (err) {
      notify(errorMessage(err), 'error');
    } finally {
      setDeleting(null);
    }
  }

  return (
    <section className="card">
      <div className="card-header">
        <h2>Requested items</h2>
        {editable && !itemsLocked && <button type="button" className="btn btn-primary btn-small" onClick={() => setEditing({})}>Add item</button>}
      </div>
      {itemsLocked && editable && <p className="muted small">Items are locked while the request is under review.</p>}
      {request.items.length ? (
        <table className="table">
          <thead><tr><th>#</th><th>Code</th><th className="desc-col">Description</th><th className="num">Quantity</th><th>Unit</th><th>Specifications</th>{editable && !itemsLocked && <th className="actions-col" />}</tr></thead>
          <tbody>
            {request.items.map((item) => (
              <tr key={item.id}>
                <td>{item.lineNumber}</td>
                <td>{item.itemCode ? <code>{item.itemCode}</code> : '—'}</td>
                <td className="desc-col">{item.description}</td>
                <td className="num">{formatNumber(item.quantity)}</td>
                <td>{item.unitOfMeasure}</td>
                <td className="pre-wrap">{item.specifications || '—'}</td>
                {editable && !itemsLocked && (
                  <td className="actions-col">
                    <button type="button" className="btn btn-small" onClick={() => setEditing(item)}>Edit</button>
                    <button type="button" className="btn btn-small btn-danger-text" onClick={() => setDeleting(item)}>Remove</button>
                  </td>
                )}
              </tr>
            ))}
          </tbody>
        </table>
      ) : <EmptyState title="No items yet">Add the goods or services you need quotations for.</EmptyState>}

      {editing && <RequestItemForm request={request} item={editing} onClose={() => setEditing(null)}
                                   onSaved={(updated) => { onChange(updated); setEditing(null); notify('Item saved'); }} />}
      {deleting && <ConfirmDialog title="Remove item" danger confirmLabel="Remove" onCancel={() => setDeleting(null)}
                                  onConfirm={remove} message={`Remove line ${deleting.lineNumber}, "${deleting.description}"?`} />}
    </section>
  );
}

function RequestItemForm({ request, item, onClose, onSaved }) {
  const [values, setValues] = useState({
    itemCode: item.itemCode || '', description: item.description || '', quantity: item.quantity ?? '',
    unitOfMeasure: item.unitOfMeasure || 'Nos',
    specifications: item.specifications || '',
  });
  const [errors, setErrors] = useState({});
  const [error, setError] = useState('');
  const [busy, setBusy] = useState(false);

  async function submit(e) {
    e.preventDefault();
    setBusy(true);
    setErrors({});
    setError('');
    const body = emptyToNull({ ...values, quantity: values.quantity === '' ? '' : Number(values.quantity) });
    try {
      onSaved(item.id
        ? await purchaseRequestsApi.updateItem(request.id, item.id, body)
        : await purchaseRequestsApi.addItem(request.id, body));
    } catch (err) {
      setErrors(fieldErrors(err));
      setError(errorMessage(err));
    } finally {
      setBusy(false);
    }
  }

  const set = (key) => (e) => setValues((v) => ({ ...v, [key]: e.target.value }));
  return (
    <Modal title={item.id ? `Edit line ${item.lineNumber}` : 'Add item'} onClose={onClose} footer={(
      <>
        <button type="button" className="btn" onClick={onClose}>Cancel</button>
        <button type="submit" form="item-form" className="btn btn-primary" disabled={busy}>{busy ? 'Saving…' : 'Save'}</button>
      </>
    )}>
      <form id="item-form" className="form-grid" onSubmit={submit} noValidate>
        <ErrorBanner message={error} />
        <Field label="Item code" error={errors.itemCode} hint="Optional, e.g. IT-001. Vendor lines with the same code match exactly">
          <input value={values.itemCode} onChange={set('itemCode')} maxLength={40} />
        </Field>
        <Field label="Description" required error={errors.description} wide>
          <input value={values.description} onChange={set('description')} placeholder="e.g. Laptop - Dell Latitude 5440" />
        </Field>
        <Field label="Quantity" required error={errors.quantity}>
          <input type="number" min="0.001" step="any" value={values.quantity} onChange={set('quantity')} />
        </Field>
        <Field label="Unit of measure" required error={errors.unitOfMeasure}>
          <input value={values.unitOfMeasure} onChange={set('unitOfMeasure')} />
        </Field>
        <Field label="Required specifications" error={errors.specifications} wide
               hint="Helps match vendor lines to this item">
          <textarea rows={3} value={values.specifications} onChange={set('specifications')} />
        </Field>
      </form>
    </Modal>
  );
}

// ------------------------------------------------------------------ quotations

function QuotationsSection({ request, quotations, canUpload, onUploaded }) {
  const navigate = useNavigate();
  return (
    <>
      {canUpload && <UploadCard request={request} onUploaded={onUploaded} />}
      <section className="card card-flush">
        <div className="card-header card-header-padded"><h2>Received quotations</h2></div>
        <ErrorBanner message={quotations.error} onRetry={quotations.reload} />
        {quotations.data?.length ? (
          <table className="table">
            <thead>
              <tr><th>Vendor</th><th>Sent by</th><th>Quotation no.</th><th className="num">Files</th><th>Status</th><th>Source</th><th className="num">Lines</th>
                <th className="num">Open flags</th><th className="num">Grand total</th><th>Valid until</th></tr>
            </thead>
            <tbody>
              {quotations.data.map((q) => (
                <tr key={q.id} className="row-link" onClick={() => navigate(`/quotations/${q.id}`)}>
                  <td><Link to={`/quotations/${q.id}`} onClick={(e) => e.stopPropagation()}>{q.vendorName}</Link></td>
                  <td>{q.submittedByVendor ? <span className="badge badge-neutral">Vendor portal</span> : <span className="muted small">Buyer ({q.uploadedBy})</span>}</td>
                  <td>{q.quotationNumber || '—'}</td>
                  <td className="num">{q.fileCount}</td>
                  <td>
                    <StatusBadge status={q.status} />
                    {IN_PROGRESS.includes(q.status) && <span className="spinner spinner-inline" aria-label="processing" />}
                    {q.status === 'FAILED' && <div className="field-error small">{q.failureReason}</div>}
                  </td>
                  <td>{q.extractionSource ? <StatusBadge status={q.extractionSource} /> : '—'}
                    {q.extractionConfidence != null && <span className="muted small"> {formatConfidence(q.extractionConfidence)}</span>}</td>
                  <td className="num">{q.itemCount}</td>
                  <td className="num">{q.openFlags || '—'}</td>
                  <td className="num">{formatMoney(q.grandTotal, q.currency || request.currency)}</td>
                  <td>{formatDate(q.validUntil)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        ) : <EmptyState title="No quotations yet">Vendors see this RFQ in their portal while it is open and send their quotations there.</EmptyState>}
      </section>
    </>
  );
}

function UploadCard({ request, onUploaded }) {
  const { notify } = useToast();
  const vendors = useApi(() => vendorsApi.list({ size: 200 }), []);
  const [vendorId, setVendorId] = useState('');
  const [files, setFiles] = useState([]);
  const [engine, setEngine] = useState('AUTO');
  const [progress, setProgress] = useState(null);
  const [error, setError] = useState('');

  async function upload(e) {
    e.preventDefault();
    if (!vendorId || !files.length) return;
    setError('');
    setProgress(0);
    try {
      const choice = parseChoice(engine);
      const q = await purchaseRequestsApi.upload(request.id, vendorId, files, setProgress, choice.engine, choice.geminiModel);
      notify(`Uploaded ${q.files.length} file(s) for ${q.vendorName}. Extracting data…`);
      setFiles([]);
      onUploaded();
    } catch (err) {
      setError(errorMessage(err));
    } finally {
      setProgress(null);
    }
  }

  return (
    <section className="card">
      <h2>Upload a quotation for a vendor</h2>
      <p className="muted small">
        Vendors normally send their quotation themselves through the vendor portal while the RFQ is open. Use this when
        a vendor e-mailed it instead: choose the vendor, then add all of its files for this quotation. Several files (for
        example a PDF quotation and an Excel price list) are read together, and any figure that differs between them is
        flagged for review.
      </p>
      <form className="upload-form" onSubmit={upload}>
        <Field label="Vendor" required>
          <select value={vendorId} onChange={(e) => setVendorId(e.target.value)} required>
            <option value="">Select vendor</option>
            {(vendors.data?.content || []).filter((v) => v.active).map((v) => <option key={v.id} value={v.id}>{v.name}</option>)}
          </select>
        </Field>
        <Field label="Quotation files" required hint="PDF, Excel (.xlsx, .xls) or Word (.docx); up to 10 files, 20 MB each">
          <FilePicker files={files} onChange={setFiles} label="Quotation files" />
        </Field>
        <Field label="Read with" hint="Automatic tries Gemini first and falls back to the rule-based reader">
          <EnginePicker id="upload-engine" value={engine} onChange={setEngine} />
        </Field>
        <button type="submit" className="btn btn-primary" disabled={!vendorId || !files.length || progress !== null}>
          {progress !== null ? `Uploading ${progress}%` : 'Upload & extract'}
        </button>
      </form>
      <ErrorBanner message={error} />
    </section>
  );
}

// ------------------------------------------------------------------ comparison

function ComparisonSection({ request, comparisons, quotations, canGenerate, onGenerated }) {
  const { notify } = useToast();
  const [selected, setSelected] = useState(0);
  const [busy, setBusy] = useState(false);
  const ready = quotations.filter((q) => q.status === 'READY_FOR_COMPARISON').length;
  const notReady = quotations.length - ready;

  async function generate() {
    setBusy(true);
    try {
      await purchaseRequestsApi.generateComparison(request.id);
      setSelected(0);
      notify('Comparison generated');
      onGenerated();
    } catch (err) {
      notify(errorMessage(err), 'error');
    } finally {
      setBusy(false);
    }
  }

  const report = comparisons.data?.[selected];
  return (
    <section className="card">
      <div className="card-header">
        <div>
          <h2>Side-by-side comparison</h2>
          <p className="muted small">
            {ready} quotation(s) ready{notReady ? `, ${notReady} still need review and will be excluded` : ''}.
            Totals are calculated by the system, not taken from the AI.
          </p>
        </div>
        <div className="btn-row">
          {comparisons.data?.length > 1 && (
            <select aria-label="Report version" value={selected} onChange={(e) => setSelected(Number(e.target.value))}>
              {comparisons.data.map((r, i) => (
                <option key={r.id} value={i}>Version {r.reportVersion}{r.latest ? ' (latest)' : ''} · {formatDateTime(r.generatedAt)}</option>
              ))}
            </select>
          )}
          {report && (
            <button type="button" className="btn"
                    onClick={() => downloadText(`${request.requestNumber}-comparison-v${report.reportVersion}.csv`,
                      comparisonToCsv(report.result, { requestNumber: request.requestNumber, reportVersion: report.reportVersion }))}>
              Export to Excel (CSV)
            </button>
          )}
          {canGenerate && (
            <button type="button" className="btn btn-primary" onClick={generate} disabled={busy || !ready}>
              {busy ? 'Generating…' : comparisons.data?.length ? 'Regenerate' : 'Generate comparison'}
            </button>
          )}
        </div>
      </div>
      {comparisons.loading && !comparisons.data ? <Spinner /> : report ? (
        <>
          <p className="muted small">Version {report.reportVersion} generated by {report.generatedBy} on {formatDateTime(report.generatedAt)}.</p>
          <ComparisonMatrix result={report.result} />
        </>
      ) : <EmptyState title="No comparison yet">Mark quotations as ready, then generate a comparison.</EmptyState>}
    </section>
  );
}
