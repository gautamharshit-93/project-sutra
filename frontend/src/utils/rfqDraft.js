/**
 * Helpers for RFQ drafts read by Groq from a dropped document or pasted text. The draft names the buyer and customer
 * as written; these match them to SUTRA's records so the buyer usually has nothing to pick.
 */

const norm = (s) => (s || '').toLowerCase().replace(/[^a-z0-9ऀ-ॿ]+/g, ' ').trim();

/** The record whose name or code best matches `name`; the only active record when there is just one. */
export function matchByName(records, name, { single = true } = {}) {
  const active = (records || []).filter((r) => r.active !== false);
  if (single && active.length === 1) return active[0];
  const target = norm(name);
  if (!target) return null;
  return active.find((r) => norm(r.name) === target || norm(r.code) === target)
    || active.find((r) => norm(r.name) && (target.includes(norm(r.name)) || norm(r.name).includes(target)))
    || null;
}

/** An empty request line, as the editable table expects. */
export const blankItem = () => ({ itemCode: '', description: '', quantity: '', unitOfMeasure: 'Nos', specifications: '' });

/** Draft (from the backend) -> form values and editable items. */
export function draftToForm(draft, entities, customers) {
  const entity = matchByName(entities, draft?.buyerName);
  const customer = matchByName(customers, draft?.customerName, { single: false });
  return {
    values: {
      title: draft?.title || '',
      description: draft?.description || '',
      entityId: entity ? String(entity.id) : '',
      subEntityId: '',
      customerId: customer ? String(customer.id) : '',
      currency: draft?.currency || 'INR',
      requiredBy: draft?.requiredBy || '',
      quotationsDue: draft?.quotationsDue || '',
    },
    items: (draft?.items || []).map((i) => ({
      itemCode: i.itemCode || '',
      description: i.description || '',
      quantity: i.quantity == null ? '' : String(i.quantity),
      unitOfMeasure: i.unitOfMeasure || 'Nos',
      specifications: i.specifications || '',
    })),
  };
}

/** True when every line can be saved as is (a description, a positive quantity and a unit). */
export function itemsComplete(items) {
  return items.length > 0 && items.every((i) => i.description.trim() && Number(i.quantity) > 0 && i.unitOfMeasure.trim());
}

/** Form values + items -> the body of POST /purchase-requests/with-items. */
export function toRequest(values, items) {
  const nullIfBlank = (v) => (v === '' || v == null ? null : v);
  return {
    request: {
      title: values.title.trim(),
      description: nullIfBlank(values.description.trim()),
      entityId: values.entityId ? Number(values.entityId) : null,
      subEntityId: values.subEntityId ? Number(values.subEntityId) : null,
      customerId: values.customerId ? Number(values.customerId) : null,
      currency: (values.currency || 'INR').toUpperCase(),
      requiredBy: nullIfBlank(values.requiredBy),
      quotationsDue: nullIfBlank(values.quotationsDue),
    },
    items: items
      .filter((i) => i.description.trim())
      .map((i) => ({
        itemCode: nullIfBlank(i.itemCode.trim()),
        description: i.description.trim(),
        quantity: Number(i.quantity),
        unitOfMeasure: i.unitOfMeasure.trim() || 'Nos',
        specifications: nullIfBlank(i.specifications.trim()),
      })),
  };
}
