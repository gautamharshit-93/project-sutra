import { useState } from 'react';
import { errorMessage, fieldErrors } from '../api/client.js';
import { customersApi, entitiesApi, purchaseRequestsApi } from '../api/endpoints.js';
import { useApi } from '../hooks/useApi.js';
import { emptyToNull } from '../utils/format.js';
import { ErrorBanner, Field, Modal } from './ui.jsx';

/** Create or edit the header of a purchase request. */
export default function PurchaseRequestForm({ request, onClose, onSaved }) {
  const entities = useApi(() => entitiesApi.list({ size: 200 }), []);
  const customers = useApi(() => customersApi.list({ size: 200 }), []);
  const [values, setValues] = useState({
    title: request?.title || '',
    description: request?.description || '',
    entityId: request?.entityId || '',
    subEntityId: request?.subEntityId || '',
    customerId: request?.customerId || '',
    currency: request?.currency || 'INR',
    requiredBy: request?.requiredBy || '',
    quotationsDue: request?.quotationsDue || '',
  });
  const [errors, setErrors] = useState({});
  const [error, setError] = useState('');
  const [busy, setBusy] = useState(false);

  const activeEntities = (entities.data?.content || []).filter((e) => e.active || e.id === values.entityId);
  const selected = activeEntities.find((e) => e.id === Number(values.entityId));

  async function submit(e) {
    e.preventDefault();
    setBusy(true);
    setError('');
    setErrors({});
    const body = emptyToNull({
      ...values,
      entityId: values.entityId ? Number(values.entityId) : '',
      subEntityId: values.subEntityId ? Number(values.subEntityId) : '',
      customerId: values.customerId ? Number(values.customerId) : '',
      currency: values.currency.toUpperCase(),
    });
    try {
      const saved = request ? await purchaseRequestsApi.update(request.id, body) : await purchaseRequestsApi.create(body);
      onSaved(saved);
    } catch (err) {
      setErrors(fieldErrors(err));
      setError(errorMessage(err));
    } finally {
      setBusy(false);
    }
  }

  const set = (key) => (e) => setValues((v) => ({ ...v, [key]: e.target.value, ...(key === 'entityId' ? { subEntityId: '' } : {}) }));
  return (
    <Modal title={request ? `Edit ${request.requestNumber}` : 'New purchase request'} onClose={onClose} footer={(
      <>
        <button type="button" className="btn" onClick={onClose}>Cancel</button>
        <button type="submit" form="pr-form" className="btn btn-primary" disabled={busy}>{busy ? 'Saving…' : 'Save'}</button>
      </>
    )}>
      <form id="pr-form" className="form-grid" onSubmit={submit} noValidate>
        <ErrorBanner message={error} />
        <Field label="Title" required error={errors.title} wide>
          <input value={values.title} onChange={set('title')} placeholder="e.g. Laptops for new joiners" />
        </Field>
        <Field label="Entity" required error={errors.entityId}>
          <select value={values.entityId} onChange={set('entityId')}>
            <option value="">Select an entity</option>
            {activeEntities.map((e) => <option key={e.id} value={e.id}>{e.code} — {e.name}</option>)}
          </select>
        </Field>
        <Field label="Sub-entity" error={errors.subEntityId}>
          <select value={values.subEntityId} onChange={set('subEntityId')} disabled={!selected?.subEntities?.length}>
            <option value="">— none —</option>
            {(selected?.subEntities || []).filter((s) => s.active).map((s) => <option key={s.id} value={s.id}>{s.name}</option>)}
          </select>
        </Field>
        <Field label="Customer" error={errors.customerId}>
          <select value={values.customerId} onChange={set('customerId')}>
            <option value="">— none —</option>
            {(customers.data?.content || []).filter((c) => c.active).map((c) => <option key={c.id} value={c.id}>{c.name}</option>)}
          </select>
        </Field>
        <Field label="Currency" required error={errors.currency} hint="3-letter ISO code">
          <input value={values.currency} maxLength={3} onChange={set('currency')} />
        </Field>
        <Field label="Quotations due by" error={errors.quotationsDue} hint="Vendors can send quotations until this day">
          <input type="date" value={values.quotationsDue} min={new Date().toLocaleDateString('en-CA')}
                 max={values.requiredBy || undefined} onChange={set('quotationsDue')} />
        </Field>
        <Field label="Goods required by" error={errors.requiredBy}>
          <input type="date" value={values.requiredBy} onChange={set('requiredBy')} />
        </Field>
        <Field label="Description" error={errors.description} wide>
          <textarea rows={3} value={values.description} onChange={set('description')} />
        </Field>
      </form>
    </Modal>
  );
}
