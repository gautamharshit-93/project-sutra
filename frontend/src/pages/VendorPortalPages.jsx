import { useEffect, useState } from 'react';
import { Link, useParams } from 'react-router-dom';
import { errorMessage, fieldErrors } from '../api/client.js';
import { vendorPortalApi, vendorProfileApi } from '../api/endpoints.js';
import { useAuth } from '../auth/AuthContext.jsx';
import FilePicker from '../components/FilePicker.jsx';
import { useToast } from '../components/Toast.jsx';
import { ConfirmDialog, EmptyState, ErrorBanner, Field, Spinner } from '../components/ui.jsx';
import { useApi } from '../hooks/useApi.js';
import { formatBytes, formatDate, formatDateTime, formatMoney, formatNumber } from '../utils/format.js';

/** Where a quotation is, in words a supplier understands. */
const STAGES = {
  RECEIVED: ['Received', 'neutral'],
  BEING_READ: ['Being read', 'info'],
  UNDER_REVIEW: ['With the buyer', 'warning'],
  ACCEPTED_FOR_COMPARISON: ['Accepted for comparison', 'success'],
  COULD_NOT_BE_READ: ['Could not be read', 'danger'],
};

const OUTCOMES = {
  PENDING: ['Decision pending', 'neutral'],
  AWARDED: ['Awarded to you', 'success'],
  NOT_AWARDED: ['Awarded to another vendor', 'warning'],
  CLOSED: ['RFQ closed without award', 'neutral'],
};

function StageBadge({ stage }) {
  if (!stage) return null;
  const [label, tone] = STAGES[stage] || [stage, 'neutral'];
  return <span className={`badge badge-${tone}`}>{label}</span>;
}

function OutcomeBadge({ outcome }) {
  if (!outcome) return null;
  const [label, tone] = OUTCOMES[outcome] || [outcome, 'neutral'];
  return <span className={`badge badge-${tone}`}>{label}</span>;
}

/** RFQs open for quotations, plus the ones this vendor has quoted on. */
export function VendorRfqsPage() {
  const { user } = useAuth();
  const rfqs = useApi(() => vendorPortalApi.rfqs({ size: 50 }), []);
  const list = rfqs.data?.content || [];
  const open = list.filter((r) => r.openForQuotations);
  const closed = list.filter((r) => !r.openForQuotations);

  return (
    <div className="page">
      <div className="hero">
        <div>
          <h1>Welcome, {user.vendorName || user.fullName}</h1>
          <p className="muted">Requests for quotation from our buyers. Open one, then send your quotation as PDF, Excel or Word.</p>
        </div>
        <Link className="btn" to="/portal/quotations">My quotations</Link>
      </div>
      <ErrorBanner message={rfqs.error} onRetry={rfqs.reload} />
      {rfqs.loading && !rfqs.data ? <Spinner label="Loading RFQs" /> : (
        <>
          <section>
            <h2>Open for quotations <span className="tab-count">{open.length}</span></h2>
            {open.length ? <RfqCards rfqs={open} /> : (
              <EmptyState title="No open RFQs right now">New requests from our buyers appear here.</EmptyState>
            )}
          </section>
          {closed.length > 0 && (
            <section>
              <h2>Closed RFQs you quoted on</h2>
              <RfqCards rfqs={closed} />
            </section>
          )}
        </>
      )}
    </div>
  );
}

function RfqCards({ rfqs }) {
  return (
    <div className="rfq-grid">
      {rfqs.map((r) => (
        <Link key={r.id} to={`/portal/rfqs/${r.id}`} className={`rfq-card${r.openForQuotations ? ' rfq-card-open' : ''}`}>
          <div className="rfq-meta"><code>{r.rfqNumber}</code> {r.buyer}</div>
          <h3>{r.title}</h3>
          <div className="rfq-meta">
            {r.itemCount} item(s) · {r.currency}
            {r.quotationsDue ? ` · quotations due ${formatDate(r.quotationsDue)}` : ''}
            {r.requiredBy ? ` · needed by ${formatDate(r.requiredBy)}` : ''}
          </div>
          <div className="rfq-meta">
            {r.myQuotationId
              ? <>Your quotation: <StageBadge stage={r.myQuotationStage} /> <OutcomeBadge outcome={r.outcome !== 'PENDING' ? r.outcome : null} /></>
              : r.openForQuotations ? <span className="badge badge-danger">Quotation not sent yet</span> : null}
          </div>
        </Link>
      ))}
    </div>
  );
}

/** One RFQ: what the buyer needs, and this vendor's quotation for it. */
export function VendorRfqDetailPage() {
  const { id } = useParams();
  const { notify } = useToast();
  const rfq = useApi(() => vendorPortalApi.rfq(id), [id]);
  const [files, setFiles] = useState([]);
  const [progress, setProgress] = useState(null);
  const [error, setError] = useState('');
  const [withdrawing, setWithdrawing] = useState(null);

  const r = rfq.data;
  const mine = r?.myQuotations || [];
  const reading = mine.some((q) => ['RECEIVED', 'BEING_READ'].includes(q.stage));
  useEffect(() => {
    if (!reading) return undefined;
    const timer = setInterval(() => rfq.reload({ silent: true }), 3000);
    return () => clearInterval(timer);
  }, [reading, rfq.reload]);

  if (rfq.loading && !r) return <Spinner label="Loading RFQ" />;
  if (!r) return <div className="page"><ErrorBanner message={rfq.error} onRetry={rfq.reload} /></div>;

  async function submit(e) {
    e.preventDefault();
    setError('');
    setProgress(0);
    try {
      await vendorPortalApi.submit(r.id, files, setProgress);
      notify('Quotation sent. The buyer has received it.');
      setFiles([]);
      rfq.reload({ silent: true });
    } catch (err) {
      setError(errorMessage(err));
    } finally {
      setProgress(null);
    }
  }

  async function withdraw() {
    try {
      await vendorPortalApi.withdraw(withdrawing.id);
      notify('Quotation withdrawn');
      rfq.reload({ silent: true });
    } catch (err) {
      notify(errorMessage(err), 'error');
    } finally {
      setWithdrawing(null);
    }
  }

  return (
    <div className="page">
      <div className="page-header">
        <div>
          <h1>{r.title} {r.openForQuotations
            ? <span className="badge badge-danger">Open</span> : <span className="badge badge-neutral">Closed</span>}</h1>
          <p className="muted">
            <code>{r.rfqNumber}</code> · {r.buyer} · prices in {r.currency}
            {r.quotationsDue ? ` · quotations due ${formatDate(r.quotationsDue)}` : ''}
            {r.requiredBy ? ` · needed by ${formatDate(r.requiredBy)}` : ''} · published {formatDate(r.publishedAt)}
          </p>
          {r.description && <p>{r.description}</p>}
        </div>
      </div>

      <section className="card card-flush">
        <div className="card-header card-header-padded"><h2>Requested items</h2></div>
        <table className="table">
          <thead><tr><th>#</th><th>Code</th><th className="desc-col">Item</th><th className="num">Quantity</th><th>Unit</th><th>Specification</th></tr></thead>
          <tbody>
            {r.items.map((i) => (
              <tr key={i.lineNumber}>
                <td>{i.lineNumber}</td>
                <td>{i.itemCode ? <code>{i.itemCode}</code> : '—'}</td>
                <td className="desc-col">{i.description}</td>
                <td className="num">{formatNumber(i.quantity)}</td>
                <td>{i.unitOfMeasure}</td>
                <td className="pre-wrap small">{i.specifications || '—'}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </section>

      {mine.length > 0 && <MyQuotations quotations={mine} onWithdraw={setWithdrawing} />}

      {!r.openForQuotations && mine.length === 0 && r.quotationsDue && (
        <div className="notice notice-info">Quotations for this RFQ were due by {formatDate(r.quotationsDue)}; it no longer accepts new ones.</div>
      )}
      {r.openForQuotations && mine.length === 0 && (
        <section className="card">
          <h2>Send your quotation</h2>
          <p className="muted small">
            Quote each item with its code (for example <code>{r.items[0]?.itemCode || 'AP-001'}</code>), quantity, unit
            price, GST and total, plus delivery, payment and warranty terms. You may send several files that together form
            one quotation, such as a PDF quotation and an Excel price list.
          </p>
          <form className="upload-form" onSubmit={submit}>
            <Field label="Quotation files" required hint="PDF, Excel (.xlsx, .xls) or Word (.docx); up to 10 files, 20 MB each" wide>
              <FilePicker files={files} onChange={setFiles} label="Quotation files" />
            </Field>
            <button type="submit" className="btn btn-primary" disabled={!files.length || progress !== null}>
              {progress !== null ? `Sending ${progress}%` : 'Send quotation'}
            </button>
          </form>
          <ErrorBanner message={error} />
        </section>
      )}

      {withdrawing && (
        <ConfirmDialog title="Withdraw quotation" danger confirmLabel="Withdraw" onCancel={() => setWithdrawing(null)}
                       onConfirm={withdraw}
                       message="Withdraw this quotation? The buyer will no longer see it. You can send a new one while the RFQ is open." />
      )}
    </div>
  );
}

function MyQuotations({ quotations, onWithdraw }) {
  return (
    <section className="card">
      <h2>Your quotation</h2>
      {quotations.map((q) => (
        <div key={q.id} className="details details-grid">
          <div><dt>Status</dt><dd><StageBadge stage={q.stage} /></dd></div>
          <div><dt>Outcome</dt><dd><OutcomeBadge outcome={q.outcome} /></dd></div>
          <div><dt>Sent</dt><dd>{formatDateTime(q.submittedAt)}{q.submittedByVendor ? '' : ' (uploaded by the buyer for you)'}</dd></div>
          <div><dt>Total confirmed by the buyer</dt><dd>{q.grandTotal != null ? formatMoney(q.grandTotal, q.currency || 'INR') : '—'}</dd></div>
          <div className="field-wide">
            <dt>Files</dt>
            <dd>
              <ul className="file-list">
                {q.files.map((f) => (
                  <li key={f.id}>
                    <span className={`file-type file-type-${f.fileFormat.toLowerCase()}`}>{f.fileFormat}</span>
                    <span className="file-name">{f.fileName}</span>
                    <span className="muted small">{formatBytes(f.sizeBytes)}</span>
                    <button type="button" className="btn btn-small"
                            onClick={() => vendorPortalApi.download(q.id, f.id, f.fileName)}>Download</button>
                  </li>
                ))}
              </ul>
            </dd>
          </div>
          {q.stage === 'COULD_NOT_BE_READ' && (
            <div className="field-wide notice notice-warning">
              Our system could not read this file automatically. The buyer can still enter it by hand, or you may withdraw
              it and send a clearer PDF or Excel file.
            </div>
          )}
          {q.canWithdraw && (
            <div className="field-wide">
              <button type="button" className="btn btn-danger-text" onClick={() => onWithdraw(q)}>Withdraw quotation</button>
            </div>
          )}
        </div>
      ))}
    </section>
  );
}

/** Every quotation this vendor has sent, with its outcome. */
export function VendorQuotationsPage() {
  const quotations = useApi(() => vendorPortalApi.quotations(), []);

  return (
    <div className="page">
      <div className="page-header">
        <div>
          <h1>My quotations</h1>
          <p className="muted">Everything you have sent, and whether it was awarded.</p>
        </div>
      </div>
      <section className="card card-flush">
        <ErrorBanner message={quotations.error} onRetry={quotations.reload} />
        {quotations.loading && !quotations.data ? <Spinner /> : quotations.data?.length ? (
          <table className="table">
            <thead><tr><th>RFQ</th><th>Title</th><th>Status</th><th>Outcome</th><th className="num">Confirmed total</th><th>Sent</th></tr></thead>
            <tbody>
              {quotations.data.map((q) => (
                <tr key={q.id}>
                  <td><Link to={`/portal/rfqs/${q.rfqId}`}><code>{q.rfqNumber}</code></Link></td>
                  <td>{q.rfqTitle}</td>
                  <td><StageBadge stage={q.stage} /></td>
                  <td><OutcomeBadge outcome={q.outcome} /></td>
                  <td className="num">{q.grandTotal != null ? formatMoney(q.grandTotal, q.currency || 'INR') : '—'}</td>
                  <td>{formatDateTime(q.submittedAt)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        ) : <EmptyState title="No quotations yet">Open an RFQ and send your quotation.</EmptyState>}
      </section>
    </div>
  );
}

const PROFILE_FIELDS = [
  ['contactPerson', 'Contact person'], ['email', 'Email'], ['phone', 'Phone'], ['taxNumber', 'GST / tax number'],
  ['website', 'Website'], ['address', 'Address'],
];

/** The supplier keeps its own contact and GST details current; the name and code stay as approved. */
export function VendorProfilePage() {
  const { notify } = useToast();
  const profile = useApi(() => vendorProfileApi.get(), []);
  const [values, setValues] = useState(null);
  const [errors, setErrors] = useState({});
  const [error, setError] = useState('');
  const [busy, setBusy] = useState(false);

  useEffect(() => {
    if (profile.data && !values) {
      setValues(Object.fromEntries(PROFILE_FIELDS.map(([k]) => [k, profile.data[k] || ''])));
    }
  }, [profile.data, values]);

  async function save(e) {
    e.preventDefault();
    setBusy(true);
    setErrors({});
    setError('');
    try {
      const body = Object.fromEntries(Object.entries(values).map(([k, v]) => [k, v.trim() || null]));
      profile.setData(await vendorProfileApi.update(body));
      notify('Company profile saved');
    } catch (err) {
      setErrors(fieldErrors(err));
      setError(errorMessage(err));
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="page">
      <div className="page-header">
        <div>
          <h1>Company profile</h1>
          <p className="muted">Buyers see these details with your quotations. Keep them current.</p>
        </div>
      </div>
      <ErrorBanner message={profile.error} onRetry={profile.reload} />
      {!values ? <Spinner /> : (
        <form className="card form-grid" onSubmit={save} noValidate>
          <ErrorBanner message={error} />
          <Field label="Company"><input value={profile.data.name} disabled /></Field>
          <Field label="Vendor code"><input value={profile.data.code} disabled /></Field>
          {PROFILE_FIELDS.map(([key, label]) => (
            <Field key={key} label={label} error={errors[key]} wide={key === 'address'}>
              {key === 'address'
                ? <textarea rows={2} value={values[key]} onChange={(e) => setValues((v) => ({ ...v, [key]: e.target.value }))} />
                : <input value={values[key]} onChange={(e) => setValues((v) => ({ ...v, [key]: e.target.value }))} />}
            </Field>
          ))}
          <div className="field-wide row-end">
            <button type="submit" className="btn btn-primary" disabled={busy}>{busy ? 'Saving…' : 'Save'}</button>
          </div>
        </form>
      )}
    </div>
  );
}
