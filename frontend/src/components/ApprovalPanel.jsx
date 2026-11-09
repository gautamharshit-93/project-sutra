import { useState } from 'react';
import { errorMessage } from '../api/client.js';
import { purchaseRequestsApi } from '../api/endpoints.js';
import { formatDateTime, formatMoney } from '../utils/format.js';
import { useToast } from './Toast.jsx';
import { EmptyState, ErrorBanner, Field, StatusBadge } from './ui.jsx';

/**
 * Records a human approval or rejection against the latest comparison. The system never pre-selects a vendor:
 * the approver must choose one, and give a reason when it is not the lowest complete quotation.
 */
export default function ApprovalPanel({ request, latest, approvals, canDecide, onDecided }) {
  const { notify } = useToast();
  const [decision, setDecision] = useState('APPROVED');
  const [selected, setSelected] = useState('');
  const [reason, setReason] = useState('');
  const [error, setError] = useState('');
  const [busy, setBusy] = useState(false);

  const columns = latest?.result?.quotations || [];
  const lowestId = latest?.lowestCompleteQuotationId;
  const chosen = columns.find((q) => q.quotationId === Number(selected));
  const reasonRequired = decision === 'REJECTED'
    || (chosen && (chosen.quotationId !== lowestId || chosen.validityExpired));
  const open = request.status === 'UNDER_REVIEW';

  async function submit(e) {
    e.preventDefault();
    setBusy(true);
    setError('');
    try {
      await purchaseRequestsApi.decide(request.id, {
        comparisonReportId: latest.id,
        decision,
        selectedQuotationId: decision === 'APPROVED' ? Number(selected) : null,
        reason: reason.trim() || null,
      });
      notify(decision === 'APPROVED' ? 'Award recorded' : 'Rejection recorded');
      onDecided();
    } catch (err) {
      setError(errorMessage(err));
    } finally {
      setBusy(false);
    }
  }

  return (
    <>
      <section className="card">
        <h2>Award this RFQ</h2>
        {!open ? (
          <p className="muted">
            {['APPROVED', 'REJECTED'].includes(request.status)
              ? `This request was ${request.status.toLowerCase()}. See the history below.`
              : 'The RFQ can be awarded once a comparison has been generated (status Under review).'}
          </p>
        ) : !canDecide ? (
          <p className="muted">Only a manager who did not raise this RFQ can award it.</p>
        ) : !latest ? (
          <p className="muted">Generate a comparison first.</p>
        ) : (
          <form className="form-grid" onSubmit={submit}>
            <ErrorBanner message={error} />
            <p className="notice notice-info field-wide">
              Awarding on comparison version {latest.reportVersion}. The system highlights the lowest complete
              quotation but never chooses a vendor itself: your award, your name and the time are recorded in the audit log.
            </p>
            <fieldset className="field field-wide">
              <legend className="field-label">Award</legend>
              <div className="checkbox-row">
                <label className="checkbox"><input type="radio" name="decision" value="APPROVED" checked={decision === 'APPROVED'}
                                                   onChange={() => setDecision('APPROVED')} /> Award to a vendor</label>
                <label className="checkbox"><input type="radio" name="decision" value="REJECTED" checked={decision === 'REJECTED'}
                                                   onChange={() => setDecision('REJECTED')} /> Reject the RFQ (no award)</label>
              </div>
            </fieldset>
            {decision === 'APPROVED' && (
              <Field label="Selected quotation" required wide>
                <select value={selected} onChange={(e) => setSelected(e.target.value)} required>
                  <option value="">Choose a vendor</option>
                  {columns.filter((q) => q.status === 'READY_FOR_COMPARISON').map((q) => (
                    <option key={q.quotationId} value={q.quotationId}>
                      {q.vendorName} — {formatMoney(q.comparableTotal, q.currency)}
                      {q.quotationId === lowestId ? ' (lowest complete)' : ''}{!q.complete ? ' (incomplete)' : ''}
                    </option>
                  ))}
                </select>
              </Field>
            )}
            <Field label="Reason" required={reasonRequired} wide
                   hint={reasonRequired ? 'Required for rejections and when not choosing the lowest complete, valid quotation' : 'Optional'}>
              <textarea rows={3} value={reason} onChange={(e) => setReason(e.target.value)} required={reasonRequired} />
            </Field>
            <div className="field-wide">
              <button type="submit" className={`btn ${decision === 'APPROVED' ? 'btn-primary' : 'btn-danger'}`}
                      disabled={busy || (decision === 'APPROVED' && !selected) || (reasonRequired && !reason.trim())}>
                {busy ? 'Recording…' : decision === 'APPROVED' ? 'Award' : 'Reject RFQ'}
              </button>
            </div>
          </form>
        )}
      </section>

      <section className="card">
        <div className="card-header">
          <h2>Award history</h2>
          {approvals.length > 0 && (
            <button type="button" className="btn" onClick={() => purchaseRequestsApi.downloadAwardSummary(request.id, request.requestNumber)
              .catch((err) => notify(errorMessage(err), 'error'))}>
              Download award summary (PDF)
            </button>
          )}
        </div>
        {approvals.length ? (
          <table className="table">
            <thead><tr><th>Decision</th><th>Awarded vendor</th><th>Reason</th><th>Comparison</th><th>Decided by</th><th>When</th></tr></thead>
            <tbody>
              {approvals.map((a) => (
                <tr key={a.id}>
                  <td><StatusBadge status={a.decision} /></td>
                  <td>{a.selectedVendorName || '—'}</td>
                  <td className="pre-wrap">{a.reason || '—'}</td>
                  <td>v{a.comparisonReportVersion}</td>
                  <td>{a.decidedBy}</td>
                  <td>{formatDateTime(a.decidedAt)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        ) : <EmptyState title="Not awarded yet" />}
      </section>
    </>
  );
}
