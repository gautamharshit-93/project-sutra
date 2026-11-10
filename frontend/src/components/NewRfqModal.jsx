import { useEffect, useState } from 'react';
import { errorMessage } from '../api/client.js';
import { customersApi, entitiesApi, purchaseRequestsApi } from '../api/endpoints.js';
import { useApi } from '../hooks/useApi.js';
import { blankItem, draftToForm, itemsComplete, toRequest } from '../utils/rfqDraft.js';
import DropZone from './DropZone.jsx';
import Icon from './Icon.jsx';
import { ErrorBanner, Field, Modal } from './ui.jsx';

/**
 * A whole RFQ in one window. Drop the RFQ document (or paste its text) and Groq fills the title, date and every
 * item; the buyer checks it and clicks Create. Entity comes first, with "+ New" right there. Nothing is saved until
 * Create, and then the header and all items are saved together.
 */
export default function NewRfqModal({ initialDraft = null, initialFiles = null, onClose, onCreated }) {
  const entities = useApi(() => entitiesApi.list({ size: 200 }), []);
  const customers = useApi(() => customersApi.list({ size: 200 }), []);
  const [values, setValues] = useState(() => draftToForm(initialDraft, [], []).values);
  const [items, setItems] = useState(() => (initialDraft ? draftToForm(initialDraft, [], []).items : []));
  const [draft, setDraft] = useState(initialDraft);
  const [showForm, setShowForm] = useState(Boolean(initialDraft));
  const [pasting, setPasting] = useState(false);
  const [pasted, setPasted] = useState('');
  const [reading, setReading] = useState(false);
  const [openNow, setOpenNow] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');

  const entityList = entities.data?.content || [];
  const customerList = customers.data?.content || [];

  // Once the entities and customers are known, match the draft's buyer / customer names to them
  useEffect(() => {
    if (!draft || entities.loading || customers.loading) return;
    const matched = draftToForm(draft, entityList, customerList).values;
    setValues((v) => ({
      ...v,
      entityId: v.entityId || matched.entityId,
      customerId: v.customerId || matched.customerId,
    }));
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [draft, entities.loading, customers.loading]);

  // Files dropped on the RFQs page before this window opened are read straight away
  useEffect(() => {
    if (initialFiles?.length) read(purchaseRequestsApi.draftFromFiles(initialFiles));
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  function applyDraft(next) {
    const form = draftToForm(next, entityList, customerList);
    setDraft(next);
    setValues((v) => ({ ...form.values, entityId: form.values.entityId || v.entityId }));
    setItems(form.items.length ? form.items : [blankItem()]);
    setShowForm(true);
    setPasting(false);
  }

  async function read(promise) {
    setReading(true);
    setError('');
    try {
      applyDraft(await promise);
    } catch (err) {
      setError(errorMessage(err));
    } finally {
      setReading(false);
    }
  }

  async function create() {
    setBusy(true);
    setError('');
    try {
      const { request, items: lines } = toRequest(values, items);
      const created = await purchaseRequestsApi.createWithItems(request, lines, openNow);
      onCreated(created);
    } catch (err) {
      setError(errorMessage(err));
    } finally {
      setBusy(false);
    }
  }

  const set = (key) => (e) => setValues((v) => ({ ...v, [key]: e.target.value, ...(key === 'entityId' ? { subEntityId: '' } : {}) }));
  const setItem = (index, key) => (e) => setItems((list) => list.map((it, i) => (i === index ? { ...it, [key]: e.target.value } : it)));
  const ready = values.title.trim() && values.entityId && itemsComplete(items.filter((i) => i.description.trim()));
  const selectedEntity = entityList.find((e) => e.id === Number(values.entityId));

  return (
    <Modal title="New RFQ" size="large" onClose={onClose} footer={showForm && (
      <>
        <label className="check open-now">
          <input type="checkbox" checked={openNow} onChange={(e) => setOpenNow(e.target.checked)} />
          Open to vendors right away
        </label>
        <button type="button" className="btn" onClick={onClose}>Cancel</button>
        <button type="button" className="btn btn-primary" disabled={!ready || busy} onClick={create}>
          {busy ? 'Creating…' : `Create RFQ${items.length ? ` with ${items.filter((i) => i.description.trim()).length} item(s)` : ''}`}
        </button>
      </>
    )}>
      <div className="new-rfq">
        <ErrorBanner message={error} />
        <DropZone busy={reading} compact={showForm} onFiles={(files) => read(purchaseRequestsApi.draftFromFiles(files))}
                  title={reading ? 'Reading your RFQ…' : showForm ? 'Drop another RFQ file to replace this draft' : 'Drop your RFQ here'}
                  hint={reading ? 'Groq is filling in the title, date and every item' : 'PDF, scanned PDF, Word or Excel. Everything is filled in for you; you just check it.'} />

        {!showForm && !pasting && (
          <div className="new-rfq-alt">
            <button type="button" className="btn btn-ghost" onClick={() => setPasting(true)}>Paste RFQ text instead</button>
            <button type="button" className="btn btn-ghost" onClick={() => { setShowForm(true); setItems([blankItem()]); }}>Fill in by hand</button>
          </div>
        )}
        {pasting && !showForm && (
          <div className="new-rfq-paste">
            <textarea rows={7} value={pasted} onChange={(e) => setPasted(e.target.value)} aria-label="RFQ text"
                      placeholder="Paste the RFQ e-mail or item list here (English, Hindi or Hinglish)" />
            <div className="row-end">
              <button type="button" className="btn" onClick={() => setPasting(false)}>Back</button>
              <button type="button" className="btn btn-primary" disabled={!pasted.trim() || reading}
                      onClick={() => read(purchaseRequestsApi.draftFromText(pasted))}>{reading ? 'Reading…' : 'Read text'}</button>
            </div>
          </div>
        )}

        {showForm && (
          <>
            {draft?.warnings?.length > 0 && (
              <div className="notice notice-warning draft-warnings">
                <div><strong>Please check</strong><ul>{draft.warnings.map((w) => <li key={w}>{w}</li>)}</ul></div>
              </div>
            )}

            <div className="form-grid">
              <EntityPicker entities={entityList} value={values.entityId} onChange={set('entityId')}
                            onCreated={(e) => { entities.reload({ silent: true }); setValues((v) => ({ ...v, entityId: String(e.id), subEntityId: '' })); }}
                            hint={draft?.buyerName && !values.entityId ? `The RFQ says “${draft.buyerName}”` : undefined} />
              <SubEntityPicker entity={selectedEntity} value={values.subEntityId} onChange={set('subEntityId')}
                               onCreated={(s) => { entities.reload({ silent: true }); setValues((v) => ({ ...v, subEntityId: String(s.id) })); }} />
              <Field label="Title" required wide>
                <input value={values.title} onChange={set('title')} placeholder="e.g. Production materials for Q1" />
              </Field>
              <Field label="Quotations due by" hint="Vendors can send quotations until this day">
                <input type="date" value={values.quotationsDue} min={new Date().toLocaleDateString('en-CA')}
                       max={values.requiredBy || undefined} onChange={set('quotationsDue')} />
              </Field>
              <Field label="Goods required by">
                <input type="date" value={values.requiredBy} onChange={set('requiredBy')} />
              </Field>
              <Field label="Currency" hint="3-letter code">
                <input value={values.currency} maxLength={3} onChange={set('currency')} />
              </Field>
              <Field label="Customer">
                <select value={values.customerId} onChange={set('customerId')}>
                  <option value="">— none —</option>
                  {customerList.filter((c) => c.active).map((c) => <option key={c.id} value={c.id}>{c.name}</option>)}
                </select>
              </Field>
              <Field label="Description" wide>
                <textarea rows={2} value={values.description} onChange={set('description')} />
              </Field>
            </div>

            <div className="rfq-items">
              <div className="rfq-items-head">
                <h3>Items <span className="tab-count">{items.length}</span></h3>
                <button type="button" className="btn btn-small" onClick={() => setItems((l) => [...l, blankItem()])}>
                  <Icon name="plus" size={14} /> Add line
                </button>
              </div>
              <div className="table-scroll">
                <table className="table rfq-items-table">
                  <thead><tr><th>#</th><th>Code</th><th>Description</th><th>Qty</th><th>Unit</th><th>Specifications</th><th /></tr></thead>
                  <tbody>
                    {items.map((it, i) => (
                      <tr key={i} className={it.description.trim() && !(Number(it.quantity) > 0) ? 'row-missing' : ''}>
                        <td>{i + 1}</td>
                        <td><input value={it.itemCode} onChange={setItem(i, 'itemCode')} aria-label={`Line ${i + 1} code`} /></td>
                        <td><input value={it.description} onChange={setItem(i, 'description')} aria-label={`Line ${i + 1} description`} /></td>
                        <td><input inputMode="decimal" value={it.quantity} onChange={setItem(i, 'quantity')} aria-label={`Line ${i + 1} quantity`} /></td>
                        <td><input value={it.unitOfMeasure} onChange={setItem(i, 'unitOfMeasure')} aria-label={`Line ${i + 1} unit`} /></td>
                        <td><input value={it.specifications} onChange={setItem(i, 'specifications')} aria-label={`Line ${i + 1} specifications`} /></td>
                        <td>
                          <button type="button" className="icon-btn" aria-label={`Remove line ${i + 1}`}
                                  onClick={() => setItems((l) => l.filter((_, j) => j !== i))}>×</button>
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
              {!values.entityId && <p className="muted small">Choose the entity (the company or plant buying) at the top.</p>}
              {values.entityId && !ready && <p className="muted small">Every line needs a description, a quantity and a unit.</p>}
            </div>
          </>
        )}
      </div>
    </Modal>
  );
}

/** Entity select with "+ New" right next to it: type the name, the code is made for you. */
function EntityPicker({ entities, value, onChange, onCreated, hint }) {
  const [adding, setAdding] = useState(false);
  const [name, setName] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');

  async function add() {
    setBusy(true);
    setError('');
    try {
      const created = await entitiesApi.create({ name: name.trim() });
      onCreated(created);
      setAdding(false);
      setName('');
    } catch (err) {
      setError(errorMessage(err));
    } finally {
      setBusy(false);
    }
  }

  return (
    <Field label="Entity (company / plant buying)" required error={error} hint={hint}>
      {adding ? (
        <div className="inline-add">
          <input autoFocus value={name} placeholder="Company or plant name" onChange={(e) => setName(e.target.value)}
                 onKeyDown={(e) => { if (e.key === 'Enter') { e.preventDefault(); if (name.trim()) add(); } }} />
          <button type="button" className="btn btn-primary btn-small" disabled={!name.trim() || busy} onClick={add}>Add</button>
          <button type="button" className="btn btn-small" onClick={() => setAdding(false)}>Cancel</button>
        </div>
      ) : (
        <div className="inline-add">
          <select value={value} onChange={onChange}>
            <option value="">Select an entity</option>
            {entities.filter((e) => e.active || e.id === Number(value)).map((e) => <option key={e.id} value={e.id}>{e.name}</option>)}
          </select>
          <button type="button" className="btn btn-small" onClick={() => setAdding(true)}><Icon name="plus" size={14} /> New</button>
        </div>
      )}
    </Field>
  );
}

/** Optional plant / department of the entity, also addable in place. */
function SubEntityPicker({ entity, value, onChange, onCreated }) {
  const [adding, setAdding] = useState(false);
  const [name, setName] = useState('');
  const [error, setError] = useState('');

  async function add() {
    setError('');
    try {
      const created = await entitiesApi.createSub(entity.id, { name: name.trim() });
      onCreated(created);
      setAdding(false);
      setName('');
    } catch (err) {
      setError(errorMessage(err));
    }
  }

  return (
    <Field label="Sub-entity (optional)" error={error}>
      {adding ? (
        <div className="inline-add">
          <input autoFocus value={name} placeholder="Plant or department" onChange={(e) => setName(e.target.value)} />
          <button type="button" className="btn btn-primary btn-small" disabled={!name.trim()} onClick={add}>Add</button>
          <button type="button" className="btn btn-small" onClick={() => setAdding(false)}>Cancel</button>
        </div>
      ) : (
        <div className="inline-add">
          <select value={value} onChange={onChange} disabled={!entity}>
            <option value="">— none —</option>
            {(entity?.subEntities || []).filter((s) => s.active).map((s) => <option key={s.id} value={s.id}>{s.name}</option>)}
          </select>
          <button type="button" className="btn btn-small" disabled={!entity} onClick={() => setAdding(true)}><Icon name="plus" size={14} /> New</button>
        </div>
      )}
    </Field>
  );
}
