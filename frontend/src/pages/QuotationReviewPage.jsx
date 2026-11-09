import { useEffect, useState } from 'react';
import { Link, useNavigate, useParams } from 'react-router-dom';
import { errorMessage, fieldErrors } from '../api/client.js';
import { purchaseRequestsApi, quotationsApi, vendorsApi } from '../api/endpoints.js';
import { useAuth } from '../auth/AuthContext.jsx';
import FilePicker from '../components/FilePicker.jsx';
import HistoryTimeline from '../components/HistoryTimeline.jsx';
import QuotationItemForm from '../components/QuotationItemForm.jsx';
import { useToast } from '../components/Toast.jsx';
import { ConfirmDialog, EmptyState, ErrorBanner, Field, Modal, Spinner, StatusBadge } from '../components/ui.jsx';
import { useApi } from '../hooks/useApi.js';
import {
  emptyToNull, formatBytes, formatConfidence, formatDate, formatDateTime, formatMoney, formatNumber,
} from '../utils/format.js';
import { can } from '../utils/permissions.js';

const IN_PROGRESS = ['UPLOADED', 'PROCESSING'];

export default function QuotationReviewPage() {
  const { id } = useParams();
  const { user } = useAuth();
  const { notify } = useToast();
  const navigate = useNavigate();
  const quotation = useApi(() => quotationsApi.get(id), [id]);
  const [showHistory, setShowHistory] = useState(false);
  const q = quotation.data;
  const request = useApi(() => (q ? purchaseRequestsApi.get(q.purchaseRequestId) : Promise.resolve(null)), [q?.purchaseRequestId]);
  const [itemForm, setItemForm] = useState(null);
  const [editingHeader, setEditingHeader] = useState(false);
  const [confirm, setConfirm] = useState(null);
  const [busy, setBusy] = useState(false);

  const processing = q && IN_PROGRESS.includes(q.status);
  useEffect(() => {
    if (!processing) return undefined;
    const timer = setInterval(() => quotation.reload({ silent: true }), 2000);
    return () => clearInterval(timer);
  }, [processing, quotation.reload]);

  if (quotation.loading && !q) return <Spinner label="Loading quotation" />;
  if (!q) return <div className="page"><ErrorBanner message={quotation.error} onRetry={quotation.reload} /></div>;

  const pr = request.data;
  const editable = q.editable && pr && can.editRequest(user, pr);
  const currency = q.currency || pr?.currency || 'INR';
  const openFlags = q.flags.filter((f) => !f.resolved);
  const openErrors = openFlags.filter((f) => f.severity === 'ERROR');

  async function run(action, success) {
    setBusy(true);
    try {
      const updated = await action();
      if (updated) quotation.setData(updated);
      notify(success);
    } catch (err) {
      notify(errorMessage(err), 'error');
    } finally {
      setBusy(false);
      setConfirm(null);
    }
  }

  return (
    <div className="page">
      <nav className="breadcrumbs">
        <Link to="/purchase-requests">Purchase requests</Link> / <Link to={`/purchase-requests/${q.purchaseRequestId}`}>{q.requestNumber}</Link> / {q.vendorName}
      </nav>
      <div className="page-header">
        <div>
          <h1>{q.vendorName} <StatusBadge status={q.status} /></h1>
          <p className="muted">
            Quotation {q.quotationNumber || '(no reference)'} · uploaded by {q.uploadedBy} on {formatDateTime(q.createdAt)}
            {q.extractionSource && <> · extracted by <StatusBadge status={q.extractionSource} /> {q.extractionConfidence != null && `(${formatConfidence(q.extractionConfidence)} confidence)`}</>}
          </p>
        </div>
        <div className="btn-row">
          {editable && <button type="button" className="btn" disabled={busy} onClick={() => setConfirm('reprocess')}>Read again</button>}
          {editable && <button type="button" className="btn btn-danger-text" disabled={busy} onClick={() => setConfirm('delete')}>Delete</button>}
        </div>
      </div>

      {processing && (
        <div className="notice notice-info"><span className="spinner spinner-inline" /> Extracting data from the document… this page updates automatically.</div>
      )}
      {q.status === 'NEEDS_REVIEW' && openErrors.length > 0 && (
        <div className="notice notice-warning" role="status">
          <span>
            {openErrors.length} line(s) have no quantity or price, so no total can be calculated. Fix them below and the
            quotation becomes ready to compare on its own.
          </span>
        </div>
      )}
      {q.status === 'FAILED' && (
        <div className="notice notice-error" role="alert">
          <div>
            <strong>Extraction failed:</strong> {q.failureReason}
            <div className="small">The original file is kept. Read it again, or enter the lines manually below.</div>
          </div>
        </div>
      )}

      <div className="grid-2">
        <section className="card">
          <div className="card-header">
            <h2>Quotation details</h2>
            {editable && <button type="button" className="btn btn-small" onClick={() => setEditingHeader(true)}>Edit</button>}
          </div>
          <dl className="details details-grid">
            <div><dt>Quotation date</dt><dd>{formatDate(q.quotationDate)}</dd></div>
            <div><dt>Valid until</dt><dd>{formatDate(q.validUntil)}</dd></div>
            <div><dt>Currency</dt><dd>{q.currency || '—'}</dd></div>
            <div><dt>Delivery</dt><dd>{q.deliveryTerms || '—'}</dd></div>
            <div><dt>Payment</dt><dd>{q.paymentTerms || '—'}</dd></div>
            <div><dt>Warranty</dt><dd>{q.warrantyTerms || '—'}</dd></div>
            {q.notes && <div className="field-wide"><dt>Notes</dt><dd>{q.notes}</dd></div>}
          </dl>
          <p className="muted small">Extraction attempt {q.processingAttempts}{q.lastProcessedAt ? ` · last run ${formatDateTime(q.lastProcessedAt)}` : ''}</p>
        </section>

        <section className="card">
          <h2>Totals</h2>
          <p className="muted small">Calculated by the system from the line items; the document's printed figures are shown for comparison.</p>
          <table className="table table-compact">
            <thead><tr><th /><th className="num">Calculated</th><th className="num">On document</th></tr></thead>
            <tbody>
              <TotalRow label="Subtotal (after discount)" calc={q.subtotal} stated={q.statedSubtotal} currency={currency} />
              <TotalRow label="Discount" calc={q.discountTotal} currency={currency} />
              <TotalRow label="Tax" calc={q.taxTotal} stated={q.statedTaxTotal} currency={currency} />
              <TotalRow label="Grand total" calc={q.grandTotal} stated={q.statedGrandTotal} currency={currency} strong />
            </tbody>
          </table>
        </section>
      </div>

      <FilesSection quotation={q} editable={editable} onChange={(updated) => quotation.setData(updated)} />

      <FlagsSection quotation={q} editable={editable} onResolved={(updated) => quotation.setData(updated)} />


      <section className="card card-flush">
        <div className="card-header card-header-padded">
          <h2>Line items</h2>
          {editable && <button type="button" className="btn btn-primary btn-small" onClick={() => setItemForm({})}>Add line</button>}
        </div>
        {q.items.length ? (
          <div className="table-scroll">
            <table className="table">
              <thead>
                <tr>
                  <th>#</th><th>Code</th><th className="desc-col">Description</th><th>Matches request line</th><th className="num">Qty</th><th>Unit</th>
                  <th className="num">Unit price</th><th className="num">Disc.</th><th className="num">Tax</th>
                  <th className="num">Line total</th><th className="num">Confidence</th>{editable && <th className="actions-col" />}
                </tr>
              </thead>
              <tbody>
                {q.items.map((item) => {
                  const itemFlags = openFlags.filter((f) => f.quotationItemId === item.id);
                  return (
                    <tr key={item.id} className={itemFlags.length ? 'row-flagged' : ''}>
                      <td>{item.lineNumber}</td>
                      <td>{item.itemCode ? <code>{item.itemCode}</code> : '—'}</td>
                      <td className="desc-col">
                        {item.description}
                        {item.manuallyEdited && <span className="badge badge-neutral">edited</span>}
                        {item.notes && <div className="muted small">{item.notes}</div>}
                      </td>
                      <td>{item.requestItemLineNumber
                        ? <>Line {item.requestItemLineNumber} <span className="muted small">({formatConfidence(item.matchConfidence)})</span></>
                        : <span className="badge badge-warning">Unmatched</span>}</td>
                      <td className="num">{formatNumber(item.quantity)}</td>
                      <td>{item.unitOfMeasure || '—'}</td>
                      <td className="num">{formatMoney(item.unitPrice, currency)}</td>
                      <td className="num">{item.discountPercent != null ? `${formatNumber(item.discountPercent, 2)}%` : item.discountAmount != null ? formatMoney(item.discountAmount, currency) : '—'}</td>
                      <td className="num">{item.taxPercent != null ? `${formatNumber(item.taxPercent, 2)}%` : item.taxAmount != null ? formatMoney(item.taxAmount, currency) : '—'}</td>
                      <td className="num">
                        <strong>{formatMoney(item.lineTotal, currency)}</strong>
                        {item.statedLineTotal != null && <div className="muted small">doc: {formatMoney(item.statedLineTotal, currency)}</div>}
                      </td>
                      <td className="num">{formatConfidence(item.extractionConfidence)}</td>
                      {editable && (
                        <td className="actions-col">
                          <button type="button" className="btn btn-small" onClick={() => setItemForm(item)}>Edit</button>
                          <button type="button" className="btn btn-small btn-danger-text"
                                  onClick={() => run(() => quotationsApi.removeItem(q.id, item.id), 'Line removed')}>Remove</button>
                        </td>
                      )}
                    </tr>
                  );
                })}
              </tbody>
            </table>
          </div>
        ) : (
          <EmptyState title="No line items">
            {editable ? 'Add the quoted lines manually, or read the files again.' : 'Nothing has been extracted yet.'}
          </EmptyState>
        )}
      </section>

      {showHistory ? (
        <HistoryTimeline load={() => quotationsApi.history(q.id)} deps={[q.id, q.status, q.items, q.flags, q.files]} />
      ) : (
        <button type="button" className="btn btn-link" onClick={() => setShowHistory(true)}>Show change history</button>
      )}

      {itemForm && pr && (
        <QuotationItemForm quotation={q} item={itemForm} requestItems={pr.items} onClose={() => setItemForm(null)}
                           onSaved={(updated) => { quotation.setData(updated); setItemForm(null); notify('Line saved; totals recalculated'); }} />
      )}
      {editingHeader && (
        <HeaderForm quotation={q} onClose={() => setEditingHeader(false)}
                    onSaved={(updated) => { quotation.setData(updated); setEditingHeader(false); notify('Details saved'); }} />
      )}
      {confirm === 'reprocess' && (
        <ConfirmDialog title="Read the files again" confirmLabel="Read again" busy={busy} onCancel={() => setConfirm(null)}
                       onConfirm={() => run(() => quotationsApi.reprocess(q.id), 'Reading the quotation again')}
                       message="This replaces the current lines, notes and any manual corrections with a fresh reading of the original files." />
      )}
      {confirm === 'delete' && (
        <ConfirmDialog title="Delete quotation" danger confirmLabel="Delete" busy={busy} onCancel={() => setConfirm(null)}
                       onConfirm={() => run(async () => { await quotationsApi.remove(q.id); navigate(`/purchase-requests/${q.purchaseRequestId}`); }, 'Quotation deleted')}
                       message="Delete this quotation and its stored file? Quotations used in a saved comparison are kept for the audit trail." />
      )}
    </div>
  );
}

/** The original files of this quotation. Adding or removing a file re-runs extraction over all files. */
function FilesSection({ quotation, editable, onChange }) {
  const { notify } = useToast();
  const [adding, setAdding] = useState([]);
  const [busy, setBusy] = useState(false);
  const files = quotation.files || [];

  async function run(action, success) {
    setBusy(true);
    try {
      onChange(await action());
      notify(success);
    } catch (err) {
      notify(errorMessage(err), 'error');
    } finally {
      setBusy(false);
    }
  }

  return (
    <section className="card">
      <div className="card-header">
        <div>
          <h2>Original files <span className="tab-count">{files.length}</span></h2>
          <p className="muted small">All files are read together as one quotation. They stay stored exactly as uploaded.</p>
        </div>
      </div>
      <ul className="file-list">
        {files.map((f) => (
          <li key={f.id}>
            <span className={`file-type file-type-${f.fileFormat.toLowerCase()}`}>{f.fileFormat}</span>
            <span className="file-name">{f.fileName}</span>
            <span className="muted small">{formatBytes(f.sizeBytes)} · uploaded {formatDateTime(f.uploadedAt)}</span>
            <button type="button" className="btn btn-small"
                    onClick={() => quotationsApi.download(quotation.id, f.id, f.fileName).catch((e) => notify(errorMessage(e), 'error'))}>
              Download
            </button>
            {editable && files.length > 1 && (
              <button type="button" className="btn btn-small btn-danger-text" disabled={busy}
                      onClick={() => run(() => quotationsApi.removeFile(quotation.id, f.id), 'File removed; extraction restarted')}>
                Remove
              </button>
            )}
          </li>
        ))}
      </ul>
      {editable && (
        <div className="add-files">
          <FilePicker files={adding} onChange={setAdding} max={10 - files.length} label="Add more files" />
          {adding.length > 0 && (
            <button type="button" className="btn btn-primary btn-small" disabled={busy}
                    onClick={() => run(async () => {
                      const updated = await quotationsApi.addFiles(quotation.id, adding);
                      setAdding([]);
                      return updated;
                    }, 'Files added; extraction restarted over all files')}>
              Add {adding.length} file(s) and re-extract
            </button>
          )}
        </div>
      )}
    </section>
  );
}

function TotalRow({ label, calc, stated, currency, strong }) {
  const differs = stated != null && calc != null && Math.abs(Number(stated) - Number(calc)) > 0.05;
  return (
    <tr>
      <td>{strong ? <strong>{label}</strong> : label}</td>
      <td className="num">{strong ? <strong>{formatMoney(calc, currency)}</strong> : formatMoney(calc, currency)}</td>
      <td className={`num${differs ? ' field-error' : ''}`}>{stated != null ? formatMoney(stated, currency) : '—'}</td>
    </tr>
  );
}

function FlagsSection({ quotation, editable, onResolved }) {
  const { notify } = useToast();
  const [showResolved, setShowResolved] = useState(false);
  const open = quotation.flags.filter((f) => !f.resolved);
  const resolved = quotation.flags.filter((f) => f.resolved);
  const shown = showResolved ? quotation.flags : open;

  async function resolve(flag) {
    try {
      onResolved(await quotationsApi.resolveFlag(quotation.id, flag.id, null));
      notify('Flag resolved');
    } catch (err) {
      notify(errorMessage(err), 'error');
    }
  }

  return (
    <section className="card">
      <div className="card-header">
        <div>
          <h2>Review flags {open.length > 0 && <span className="tab-count">{open.length}</span>}</h2>
          <p className="muted small">Uncertain, conflicting or missing values. Errors must be fixed; warnings can be accepted after checking.</p>
        </div>
        {resolved.length > 0 && (
          <label className="checkbox small">
            <input type="checkbox" checked={showResolved} onChange={(e) => setShowResolved(e.target.checked)} /> Show resolved ({resolved.length})
          </label>
        )}
      </div>
      {shown.length ? (
        <ul className="flags">
          {shown.map((f) => (
            <li key={f.id} className={`flag flag-${f.severity.toLowerCase()}${f.resolved ? ' flag-resolved' : ''}`}>
              <StatusBadge status={f.severity} />
              <span className="flag-message">{f.message}</span>
              {f.resolved ? (
                <span className="muted small">resolved by {f.resolvedBy} {formatDateTime(f.resolvedAt)}</span>
              ) : editable && f.severity !== 'ERROR' ? (
                <button type="button" className="btn btn-small" onClick={() => resolve(f)}>Accept</button>
              ) : null}
            </li>
          ))}
        </ul>
      ) : <p className="muted">No open flags.</p>}
    </section>
  );
}

function HeaderForm({ quotation, onClose, onSaved }) {
  const vendors = useApi(() => vendorsApi.list({ size: 200 }), []);
  const [values, setValues] = useState({
    vendorId: quotation.vendorId,
    quotationNumber: quotation.quotationNumber || '',
    quotationDate: quotation.quotationDate || '',
    validUntil: quotation.validUntil || '',
    currency: quotation.currency || '',
    statedSubtotal: quotation.statedSubtotal ?? '',
    statedTaxTotal: quotation.statedTaxTotal ?? '',
    statedGrandTotal: quotation.statedGrandTotal ?? '',
    deliveryTerms: quotation.deliveryTerms || '',
    paymentTerms: quotation.paymentTerms || '',
    warrantyTerms: quotation.warrantyTerms || '',
    notes: quotation.notes || '',
  });
  const [errors, setErrors] = useState({});
  const [error, setError] = useState('');
  const [busy, setBusy] = useState(false);

  async function submit(e) {
    e.preventDefault();
    setBusy(true);
    setError('');
    setErrors({});
    try {
      onSaved(await quotationsApi.updateHeader(quotation.id, emptyToNull({ ...values, currency: values.currency.toUpperCase() })));
    } catch (err) {
      setErrors(fieldErrors(err));
      setError(errorMessage(err));
    } finally {
      setBusy(false);
    }
  }

  const set = (key) => (e) => setValues((v) => ({ ...v, [key]: e.target.value }));
  return (
    <Modal title="Correct quotation details" size="large" onClose={onClose} footer={(
      <>
        <button type="button" className="btn" onClick={onClose}>Cancel</button>
        <button type="submit" form="header-form" className="btn btn-primary" disabled={busy}>{busy ? 'Saving…' : 'Save'}</button>
      </>
    )}>
      <form id="header-form" className="form-grid form-grid-3" onSubmit={submit} noValidate>
        <ErrorBanner message={error} />
        <Field label="Vendor" required error={errors.vendorId}>
          <select value={values.vendorId} onChange={(e) => setValues((v) => ({ ...v, vendorId: Number(e.target.value) }))}>
            {(vendors.data?.content || []).filter((v) => v.active || v.id === quotation.vendorId)
              .map((v) => <option key={v.id} value={v.id}>{v.name}</option>)}
          </select>
        </Field>
        <Field label="Quotation number" error={errors.quotationNumber}><input value={values.quotationNumber} onChange={set('quotationNumber')} /></Field>
        <Field label="Currency" error={errors.currency}><input maxLength={3} value={values.currency} onChange={set('currency')} /></Field>
        <Field label="Quotation date" error={errors.quotationDate}><input type="date" value={values.quotationDate} onChange={set('quotationDate')} /></Field>
        <Field label="Valid until" error={errors.validUntil || errors.validityRangeValid}><input type="date" value={values.validUntil} onChange={set('validUntil')} /></Field>
        <span />
        <Field label="Subtotal on document" error={errors.statedSubtotal}><input type="number" step="0.01" min="0" value={values.statedSubtotal} onChange={set('statedSubtotal')} /></Field>
        <Field label="Tax on document" error={errors.statedTaxTotal}><input type="number" step="0.01" min="0" value={values.statedTaxTotal} onChange={set('statedTaxTotal')} /></Field>
        <Field label="Grand total on document" error={errors.statedGrandTotal}><input type="number" step="0.01" min="0" value={values.statedGrandTotal} onChange={set('statedGrandTotal')} /></Field>
        <Field label="Delivery terms" wide><textarea rows={2} value={values.deliveryTerms} onChange={set('deliveryTerms')} /></Field>
        <Field label="Payment terms" wide><textarea rows={2} value={values.paymentTerms} onChange={set('paymentTerms')} /></Field>
        <Field label="Warranty" wide><textarea rows={2} value={values.warrantyTerms} onChange={set('warrantyTerms')} /></Field>
        <Field label="Notes" wide><textarea rows={2} value={values.notes} onChange={set('notes')} /></Field>
      </form>
    </Modal>
  );
}
