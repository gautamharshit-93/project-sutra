import { useState } from 'react';
import { errorMessage } from '../api/client.js';
import { quotationsApi } from '../api/endpoints.js';
import { engineName } from './EnginePicker.jsx';
import { GradeBadge, GradeRules } from './Grade.jsx';
import { ErrorBanner, StatusBadge } from './ui.jsx';
import { formatDate, formatMoney, formatNumber } from '../utils/format.js';

/**
 * Final check before review: reads the quotation with every available engine (or the chosen ones), and shows the
 * readings side by side, matched, recalculated and graded like a stored extraction. Nothing is saved; the user can
 * re-run the extraction with the engine they trust.
 */
export default function CrossCheckPanel({ quotation, editable, onUseEngine }) {
  const [result, setResult] = useState(null);
  const [error, setError] = useState('');
  const [running, setRunning] = useState(false);
  const [openRules, setOpenRules] = useState(null);
  const currency = quotation.currency || 'INR';

  async function run() {
    setRunning(true);
    setError('');
    try {
      setResult(await quotationsApi.crossCheck(quotation.id));
    } catch (err) {
      setError(errorMessage(err));
    } finally {
      setRunning(false);
    }
  }

  const ok = result?.runs.filter((r) => r.ok) || [];
  // Requested lines read by any engine, so the per-line table lines up across engines
  const lineKeys = [...new Set(ok.flatMap((r) => r.lines.map(lineKey)))];

  return (
    <section className="card">
      <div className="card-header">
        <div>
          <h2>Cross-check extraction engines</h2>
          <p className="muted small">
            Reads the original files again with each available engine and compares the readings. Nothing is saved:
            the current lines stay until you re-run the extraction with the engine you trust.
          </p>
        </div>
        {editable && (
          <button type="button" className="btn" disabled={running || quotation.status === 'PROCESSING'} onClick={run}>
            {running ? 'Reading with every engine…' : result ? 'Run again' : 'Cross-check'}
          </button>
        )}
      </div>
      {running && <p className="muted small"><span className="spinner spinner-inline" /> The engines run one after another; this can take a minute or two.</p>}
      <ErrorBanner message={error} />

      {result && (
        <>
          <div className={`notice ${ok.length >= 2 && result.totalsAgree ? 'notice-success' : 'notice-warning'}`} role="status">
            <span>{result.summary}</span>
          </div>
          <div className="table-scroll">
            <table className="table crosscheck">
              <thead>
                <tr>
                  <th className="sticky-col" />
                  {quotation.extractionSource && <th>Stored now <StatusBadge status={quotation.extractionSource} /></th>}
                  {result.runs.map((r) => (
                    <th key={r.engine} className={r.engine === result.bestEngine ? 'col-best' : ''}>
                      {engineName(r.engine)}
                      <div className="muted small">{r.model} · {(r.durationMs / 1000).toFixed(1)} s</div>
                      {r.engine === result.bestEngine && <span className="badge badge-success">Highest quality</span>}
                    </th>
                  ))}
                </tr>
              </thead>
              <tbody>
                <Row label="Quality grade" current={<GradeBadge report={quotation.extractionQuality} />} runs={result.runs}
                     render={(r) => (
                       <>
                         <GradeBadge report={r.quality} />{' '}
                         <button type="button" className="btn btn-link small"
                                 onClick={() => setOpenRules(openRules === r.engine ? null : r.engine)}>
                           {openRules === r.engine ? 'Hide rules' : 'Rules'}
                         </button>
                       </>
                     )} />
                <Row label="Calculated grand total" current={formatMoney(quotation.grandTotal, currency)} runs={result.runs}
                     render={(r) => <strong>{formatMoney(r.calculatedGrandTotal, r.currency || currency)}</strong>} />
                <Row label="Printed grand total" current={formatMoney(quotation.statedGrandTotal, currency)} runs={result.runs}
                     render={(r) => formatMoney(r.statedGrandTotal, r.currency || currency)} />
                <Row label="Lines read / requested items matched" current={`${quotation.items.length} / ${new Set(quotation.items.map((i) => i.requestItemId).filter(Boolean)).size}`}
                     runs={result.runs} render={(r) => `${r.itemCount} / ${r.matchedItems} of ${r.requestedItems}`} />
                <Row label="Quotation number" current={quotation.quotationNumber || '—'} runs={result.runs} render={(r) => r.quotationNumber || '—'} />
                <Row label="Valid until" current={formatDate(quotation.validUntil)} runs={result.runs} render={(r) => formatDate(r.validUntil)} />
                <Row label="Open review flags" current={quotation.flags.filter((f) => !f.resolved).length} runs={result.runs}
                     render={(r) => (
                       <span title={r.flags.join('\n')}>{r.openFlags}</span>
                     )} />
                {lineKeys.map((key) => (
                  <Row key={key} label={<span className="small">{key}</span>}
                       current={lineTotal(quotation.items.find((i) => lineKey(currentLine(i)) === key), currency)}
                       runs={result.runs}
                       render={(r) => lineTotal(r.lines.find((l) => lineKey(l) === key), r.currency || currency, true)} />
                ))}
                {editable && (
                  <Row label="" runs={result.runs} current={null}
                       render={(r) => (
                         <button type="button" className="btn btn-small" onClick={() => onUseEngine(r.engine)}>
                           Use {engineName(r.engine)}
                         </button>
                       )} />
                )}
              </tbody>
            </table>
          </div>
          {openRules && (
            <div className="crosscheck-rules">
              <h3>{engineName(openRules)}: how the quality grade is made up</h3>
              <GradeRules report={result.runs.find((r) => r.engine === openRules)?.quality} />
            </div>
          )}
        </>
      )}
    </section>
  );

  function Row({ label, current, runs, render }) {
    return (
      <tr>
        <th scope="row" className="sticky-col">{label}</th>
        {quotation.extractionSource && <td>{current}</td>}
        {runs.map((r) => (
          <td key={r.engine}>{r.ok ? render(r) : <span className="field-error small">{label === 'Quality grade' ? r.error : '—'}</span>}</td>
        ))}
      </tr>
    );
  }
}

/** Lines are lined up by the requested line they match (or item code / description when unmatched). */
function lineKey(line) {
  if (line.requestLine != null) return `Request line ${line.requestLine}`;
  return `Unmatched: ${line.itemCode || line.description}`;
}

function currentLine(item) {
  return { requestLine: item.requestItemLineNumber, itemCode: item.itemCode, description: item.description };
}

function lineTotal(line, currency, withQuantity) {
  if (!line) return <span className="muted">not read</span>;
  return (
    <>
      {formatMoney(line.lineTotal, currency)}
      {withQuantity && line.quantity != null && (
        <div className="muted small">{formatNumber(line.quantity)} × {formatMoney(line.unitPrice, currency)}</div>
      )}
    </>
  );
}
