import CrudPage from '../components/CrudPage.jsx';
import { customersApi, entitiesApi, vendorsApi } from '../api/endpoints.js';
import { useApi } from '../hooks/useApi.js';

const contactFields = [
  { name: 'contactPerson', label: 'Contact person' },
  { name: 'email', label: 'Email', type: 'email' },
  { name: 'phone', label: 'Phone' },
  { name: 'address', label: 'Address', type: 'textarea', wide: true },
];

export function VendorsPage() {
  return (
    <CrudPage
      title="Vendors" singular="Vendor" api={vendorsApi} allowCreate={false}
      description="Suppliers who send quotations, with their contact and business details."
      notice="Vendors register themselves on the vendor portal (Sign in > Register your company). New sign-ups wait for one-click approval under Users & roles; vendors keep their own contact and GST details up to date."
      fields={[
        { name: 'code', label: 'Code', required: true, hint: 'Letters, digits, dash or underscore' },
        { name: 'name', label: 'Name', required: true },
        ...contactFields,
        { name: 'taxNumber', label: 'Tax / GST number' },
        { name: 'website', label: 'Website' },
      ]}
      columns={[
        { label: 'Code', render: (v) => <code>{v.code}</code> },
        { label: 'Name', render: (v) => v.name },
        { label: 'Contact', render: (v) => [v.contactPerson, v.email, v.phone].filter(Boolean).join(' · ') || '—' },
        { label: 'Tax number', render: (v) => v.taxNumber || '—' },
      ]}
      toForm={(v) => v}
    />
  );
}

export function CustomersPage() {
  const entities = useApi(() => entitiesApi.list({ size: 200 }), []);
  const entityOptions = (entities.data?.content || []).map((e) => ({ value: e.id, label: `${e.code} — ${e.name}` }));
  return (
    <CrudPage
      title="Customers" singular="Customer" api={customersApi}
      description="Customers that purchase requests can be raised for."
      fields={[
        { name: 'code', label: 'Code', required: true },
        { name: 'name', label: 'Name', required: true },
        { name: 'entityId', label: 'Entity', type: 'select', options: entityOptions },
        ...contactFields,
      ]}
      columns={[
        { label: 'Code', render: (c) => <code>{c.code}</code> },
        { label: 'Name', render: (c) => c.name },
        { label: 'Entity', render: (c) => c.entityName || '—' },
        { label: 'Contact', render: (c) => [c.contactPerson, c.email, c.phone].filter(Boolean).join(' · ') || '—' },
      ]}
      toForm={(c) => c}
    />
  );
}
