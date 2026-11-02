import { Link } from 'react-router-dom';
import { formatConfidence, formatDate, formatMoney, formatNumber, formatPercent } from '../utils/format.js';
import { useState } from 'react';
import { GradeBadge, GradeRules } from './Grade.jsx';
import { StatusBadge } from './ui.jsx';

/**
 * Renders a ComparisonResult: one column per quotation, one row per requested item, then totals and
 * commercial terms. The lowest offer per item and the lowest complete quotation are highlighted, but
 * nothing is selected - the disclaimer from the backend is always shown.
 */
export default function ComparisonMatrix({ result }) {
  const { quotations, rows, unmatchedItems, warnings, requestCurrency, lowestCompleteQuotationId, disclaimer } = result;
  const [gradeRules, setGradeRules] = useState(null);
  if (!quotations.length) return <p className="muted">No quotations in this comparison.</p>;
  const money = (v, q) => formatMoney(v, q?.currency || requestCurrency);

  return (
    <div className="comparison">
      <p className="disclaimer" role="note">{disclaimer}</p>
      {warnings.length > 0 && (
        <ul className="notice notice-warning warnings">
          {warnings.map((w) => <li key={w}>{w}</li>)}
        </ul>
      )}

      <div className="table-scroll">
        <table className="table matrix">
          <thead>
            <tr>
              <th className="sticky-col">Requested item</th>
              {quotations.map((q) => (
                <th key={q.quotationId} className={q.quotationId === lowestCompleteQuotationId ? 'col-best' : ''}>
                  <Link to={`/quotations/${q.quotationId}`}>{q.vendorName}</Link>
                  <div className="muted small">{q.quotationNumber || 'no ref.'}</div>
                  {q.quotationId === lowestCompleteQuotationId && <span className="badge badge-success">Lowest complete</span>}
                  {q.priceRank && q.quotationId !== lowestCompleteQuotationId && <span className="badge badge-neutral">Rank {q.priceRank}</span>}
                  {!q.complete && <span className="badge badge-warning">Incomplete</span>}
                </th>
              ))}
            </tr>
          </thead>
          <tbody>
            {rows.map((row) => (
              <tr key={row.requestItemId}>
                <th scope="row" className="sticky-col">
                  <span className="muted small">#{row.lineNumber}</span>{' '}
                  {row.itemCode && <code>{row.itemCode}</code>} {row.description}
                  <div className="muted small">{formatNumber(row.quantity)} {row.unitOfMeasure}
                    {row.priceSpreadPercent != null && ` · spread ${formatPercent(row.priceSpreadPercent)}`}</div>
                </th>
                {row.cells.map((cell) => {
                  const q = quotations.find((c) => c.quotationId === cell.quotationId);
                  if (cell.status === 'MISSING') {
                    return <td key={cell.quotationId} className="cell-missing">Not quoted</td>;
                  }
                  return (
                    <td key={cell.quotationId} className={cell.lowest ? 'cell-best' : ''}>
                      <div className="cell-main">{money(cell.comparableLineTotal, q)}</div>
                      <div className="small">{money(cell.effectiveUnitPrice, q)} / unit incl. tax</div>
                      {cell.differencePercent != null && Number(cell.differencePercent) !== 0 && (
                        <div className="small diff">{formatPercent(cell.differencePercent)} vs lowest</div>
                      )}
                      <div className="muted small" title={cell.quotedDescription}>{cell.quotedDescription}</div>
                      {cell.quantityMismatch && (
                        <div className="small field-error">Quoted qty {formatNumber(cell.quotedQuantity)} (normalised)</div>
                      )}
                      {cell.matchConfidence != null && Number(cell.matchConfidence) < 0.6 && (
                        <div className="small field-error">Match {formatConfidence(cell.matchConfidence)}</div>
                      )}
                    </td>
                  );
                })}
              </tr>
            ))}
          </tbody>
          <tbody className="matrix-summary">
            <SummaryRow label="Comparable total (requested qty)" quotations={quotations} lowest={lowestCompleteQuotationId}
                        render={(q) => <strong>{money(q.comparableTotal, q)}</strong>} />
            <SummaryRow label="Difference from lowest" quotations={quotations}
                        render={(q) => (q.differenceFromLowest == null ? '—'
                          : Number(q.differenceFromLowest) === 0 ? 'Lowest'
                            : `${money(q.differenceFromLowest, q)} (${formatPercent(q.differencePercent)})`)} />
            <SummaryRow label="Quotation grand total" quotations={quotations} render={(q) => money(q.grandTotal, q)} />
            <SummaryRow label="Tax" quotations={quotations} render={(q) => money(q.taxTotal, q)} />
            <SummaryRow label="Items quoted" quotations={quotations}
                        render={(q) => `${q.itemsQuoted} of ${q.itemsQuoted + q.itemsMissing}`} />
            <SummaryRow label="Valid until" quotations={quotations}
                        render={(q) => <span className={q.validityExpired ? 'field-error' : ''}>{formatDate(q.validUntil)}{q.validityExpired ? ' (expired)' : ''}</span>} />
            <SummaryRow label="Delivery terms" quotations={quotations} render={(q) => q.deliveryTerms || <span className="muted">Not stated</span>} />
            <SummaryRow label="Payment terms" quotations={quotations} render={(q) => q.paymentTerms || <span className="muted">Not stated</span>} />
            <SummaryRow label="Warranty" quotations={quotations} render={(q) => q.warrantyTerms || <span className="muted">Not stated</span>} />
            <SummaryRow label="Missing information" quotations={quotations}
                        render={(q) => (q.missingInformation.length ? q.missingInformation.join(', ') : 'None')} />
            {quotations.some((q) => q.grade) && (
              <SummaryRow label={<>Advisory grade <span className="muted small">(price, coverage, terms; not a selection)</span></>}
                          quotations={quotations}
                          render={(q) => (q.grade ? (
                            <>
                              <GradeBadge report={q.grade} />{' '}
                              <button type="button" className="btn btn-link small"
                                      onClick={() => setGradeRules(gradeRules === q.quotationId ? null : q.quotationId)}>
                                {gradeRules === q.quotationId ? 'Hide rules' : 'Rules'}
                              </button>
                            </>
                          ) : '—')} />
            )}
            <SummaryRow label="Review status" quotations={quotations}
                        render={(q) => <><StatusBadge status={q.status} /> {q.openFlags > 0 && <span className="small">{q.openFlags} open flag(s)</span>}</>} />
          </tbody>
        </table>
      </div>

      {gradeRules && (
        <div className="grade-panel">
          <h3>{quotations.find((q) => q.quotationId === gradeRules)?.vendorName}: advisory grade</h3>
          <GradeRules report={quotations.find((q) => q.quotationId === gradeRules)?.grade} />
        </div>
      )}

      {unmatchedItems.length > 0 && (
        <div className="unmatched">
          <h3>Quoted lines that match no requested item</h3>
          <ul>
            {unmatchedItems.map((u) => (
              <li key={u.quotationItemId}>
                <strong>{u.vendorName}:</strong> {u.description} — {formatMoney(u.lineTotal, requestCurrency)}
              </li>
            ))}
          </ul>
        </div>
      )}
    </div>
  );
}

function SummaryRow({ label, quotations, render, lowest }) {
  return (
    <tr>
      <th scope="row" className="sticky-col">{label}</th>
      {quotations.map((q) => (
        <td key={q.quotationId} className={lowest && q.quotationId === lowest ? 'cell-best' : ''}>{render(q)}</td>
      ))}
    </tr>
  );
}
