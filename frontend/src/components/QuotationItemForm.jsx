import { useState } from 'react';
import { errorMessage, fieldErrors } from '../api/client.js';
import { quotationsApi } from '../api/endpoints.js';
import { ErrorBanner, Field, Modal } from './ui.jsx';

const NUMERIC = ['quantity', 'unitPrice', 'discountPercent', 'discountAmount', 'taxPercent', 'taxAmount', 'statedLineTotal'];

/** Validates a quoted line on the client; returns { field: message }. The server validates again. */
export function validateItem(values) {
  const errors = {};
  if (!values.description?.trim()) errors.description = 'Description is required';
  for (const key of NUMERIC) {
    if (values[key] === '' || values[key] === null || values[key] === undefined) continue;
    const n = Number(values[key]);
    if (Number.isNaN(n)) errors[key] = 'Must be a number';
    else if (n < 0) errors[key] = 'Cannot be negative';
  }
  if (!errors.quantity && values.quantity !== '' && Number(values.quantity) === 0) errors.quantity = 'Must be greater than 0';
  for (const key of ['discountPercent', 'taxPercent']) {
    if (!errors[key] && values[key] !== '' && Number(values[key]) > 100) errors[key] = 'Must be between 0 and 100';
  }
  return errors;
}

/** Add or correct a quotation line. The backend recalculates the line total and quotation totals. */
export default function QuotationItemForm({ quotation, item, requestItems, onClose, onSaved }) {
  const [values, setValues] = useState({
    requestItemId: item.requestItemId ?? '',
    itemCode: item.itemCode || '',
    description: item.description || '',
    quantity: item.quantity ?? '',
    unitOfMeasure: item.unitOfMeasure || '',
    unitPrice: item.unitPrice ?? '',
    discountPercent: item.discountPercent ?? '',
    discountAmount: item.discountAmount ?? '',
    taxPercent: item.taxPercent ?? '',
    taxAmount: item.taxAmount ?? '',
    statedLineTotal: item.statedLineTotal ?? '',
    notes: item.notes || '',
  });
  const [errors, setErrors] = useState({});
  const [error, setError] = useState('');
  const [busy, setBusy] = useState(false);

  async function submit(e) {
    e.preventDefault();
    const clientErrors = validateItem(values);
    setErrors(clientErrors);
    if (Object.keys(clientErrors).length) return;
    setBusy(true);
    setError('');
    const body = { ...values, requestItemId: values.requestItemId === '' ? null : Number(values.requestItemId) };
    for (const key of NUMERIC) body[key] = values[key] === '' ? null : Number(values[key]);
    for (const key of ['itemCode', 'unitOfMeasure', 'notes']) body[key] = values[key]?.trim() ? values[key].trim() : null;
    try {
      onSaved(item.id
        ? await quotationsApi.updateItem(quotation.id, item.id, body)
        : await quotationsApi.addItem(quotation.id, body));
    } catch (err) {
      setErrors(fieldErrors(err));
      setError(errorMessage(err));
    } finally {
      setBusy(false);
    }
  }

  const set = (key) => (e) => setValues((v) => ({ ...v, [key]: e.target.value }));
  const num = (key, label, step = '0.01') => (
    <Field label={label} error={errors[key]}>
      <input type="number" step={step} min="0" value={values[key]} onChange={set(key)} aria-label={label} />
    </Field>
  );
  return (
    <Modal title={item.id ? `Correct line ${item.lineNumber}` : 'Add quoted line'} size="large" onClose={onClose} footer={(
      <>
        <button type="button" className="btn" onClick={onClose}>Cancel</button>
        <button type="submit" form="qitem-form" className="btn btn-primary" disabled={busy}>{busy ? 'Saving…' : 'Save'}</button>
      </>
    )}>
      <form id="qitem-form" className="form-grid form-grid-3" onSubmit={submit} noValidate>
        <ErrorBanner message={error} />
        <Field label="Item code" error={errors.itemCode}>
          <input value={values.itemCode} onChange={set('itemCode')} maxLength={40} aria-label="Item code" />
        </Field>
        <Field label="Description" required error={errors.description} wide>
          <input value={values.description} onChange={set('description')} aria-label="Description" />
        </Field>
        <Field label="Matches requested item" error={errors.requestItemId} wide>
          <select value={values.requestItemId} onChange={set('requestItemId')} aria-label="Matches requested item">
            <option value="">Not part of the request (extra line)</option>
            {requestItems.map((r) => (
              <option key={r.id} value={r.id}>
                Line {r.lineNumber}{r.itemCode ? ` [${r.itemCode}]` : ''}: {r.description} ({r.quantity} {r.unitOfMeasure})
              </option>
            ))}
          </select>
        </Field>
        {num('quantity', 'Quantity', 'any')}
        <Field label="Unit" error={errors.unitOfMeasure}><input value={values.unitOfMeasure} onChange={set('unitOfMeasure')} /></Field>
        {num('unitPrice', 'Unit price', 'any')}
        {num('discountPercent', 'Discount %')}
        {num('discountAmount', 'Discount amount')}
        <span />
        {num('taxPercent', 'Tax %')}
        {num('taxAmount', 'Tax amount')}
        {num('statedLineTotal', 'Line total on document')}
        <Field label="Notes" wide><input value={values.notes} onChange={set('notes')} /></Field>
        <p className="muted small field-wide">Give either a percentage or an amount for discount and tax. The line total is always recalculated by the system.</p>
      </form>
    </Modal>
  );
}
