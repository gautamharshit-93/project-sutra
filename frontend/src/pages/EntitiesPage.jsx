import { useState } from 'react';
import { errorMessage } from '../api/client.js';
import { entitiesApi } from '../api/endpoints.js';
import { useAuth } from '../auth/AuthContext.jsx';
import { ActiveBadge, RecordForm } from '../components/CrudPage.jsx';
import { useToast } from '../components/Toast.jsx';
import { ConfirmDialog, EmptyState, ErrorBanner, Pagination, Spinner } from '../components/ui.jsx';
import { useApi } from '../hooks/useApi.js';
import { emptyToNull } from '../utils/format.js';
import { can } from '../utils/permissions.js';

const ENTITY_FIELDS = [
  { name: 'code', label: 'Code', required: true },
  { name: 'name', label: 'Name', required: true },
  { name: 'description', label: 'Description', type: 'textarea', wide: true },
  { name: 'address', label: 'Address', type: 'textarea', wide: true },
];
const SUB_FIELDS = [
  { name: 'code', label: 'Code', required: true },
  { name: 'name', label: 'Name', required: true },
  { name: 'description', label: 'Description', type: 'textarea', wide: true },
];

/** Parent entities with their sub-entities shown inline. */
export default function EntitiesPage() {
  const { user } = useAuth();
  const { notify } = useToast();
  const editable = can.manageMasterData(user);
  const [query, setQuery] = useState('');
  const [page, setPage] = useState(0);
  const [form, setForm] = useState(null); // { kind: 'entity'|'sub', entity?, record? }
  const [deleting, setDeleting] = useState(null); // { kind, entity, record }
  const [busy, setBusy] = useState(false);
  const list = useApi(() => entitiesApi.list({ q: query || undefined, page, size: 10 }), [query, page]);

  async function save(values) {
    const body = emptyToNull(values);
    if (form.kind === 'entity') {
      if (form.record) await entitiesApi.update(form.record.id, body);
      else await entitiesApi.create(body);
    } else if (form.record) {
      await entitiesApi.updateSub(form.entity.id, form.record.id, body);
    } else {
      await entitiesApi.createSub(form.entity.id, body);
    }
    notify(`${form.kind === 'entity' ? 'Entity' : 'Sub-entity'} saved`);
    setForm(null);
    list.reload();
  }

  async function remove() {
    setBusy(true);
    try {
      if (deleting.kind === 'entity') await entitiesApi.remove(deleting.record.id);
      else await entitiesApi.removeSub(deleting.entity.id, deleting.record.id);
      notify('Deleted');
      list.reload();
    } catch (err) {
      notify(errorMessage(err), 'error');
    } finally {
      setBusy(false);
      setDeleting(null);
    }
  }

  return (
    <div className="page">
      <div className="page-header">
        <div>
          <h1>Entities</h1>
          <p className="muted">Parent organisations and the sub-entities (departments, branches) linked to them.</p>
        </div>
        {editable && <button type="button" className="btn btn-primary" onClick={() => setForm({ kind: 'entity' })}>New entity</button>}
      </div>
      <div className="toolbar">
        <input type="search" className="search" placeholder="Search entities" aria-label="Search"
               value={query} onChange={(e) => { setQuery(e.target.value); setPage(0); }} />
      </div>
      <ErrorBanner message={list.error} onRetry={list.reload} />

      {list.loading && !list.data ? <Spinner /> : list.data?.content?.length ? (
        <>
          {list.data.content.map((entity) => (
            <section key={entity.id} className="card">
              <div className="card-header">
                <div>
                  <h2><code>{entity.code}</code> {entity.name} <ActiveBadge active={entity.active} /></h2>
                  {entity.description && <p className="muted">{entity.description}</p>}
                  {entity.address && <p className="muted small">{entity.address}</p>}
                </div>
                {editable && (
                  <div className="btn-row">
                    <button type="button" className="btn btn-small" onClick={() => setForm({ kind: 'sub', entity })}>Add sub-entity</button>
                    <button type="button" className="btn btn-small" onClick={() => setForm({ kind: 'entity', record: entity })}>Edit</button>
                    <button type="button" className="btn btn-small btn-danger-text"
                            onClick={() => setDeleting({ kind: 'entity', record: entity })}>Delete</button>
                  </div>
                )}
              </div>
              {entity.subEntities.length ? (
                <table className="table table-compact">
                  <thead><tr><th>Code</th><th>Sub-entity</th><th>Description</th><th>Status</th>{editable && <th className="actions-col" />}</tr></thead>
                  <tbody>
                    {entity.subEntities.map((sub) => (
                      <tr key={sub.id}>
                        <td><code>{sub.code}</code></td>
                        <td>{sub.name}</td>
                        <td>{sub.description || '—'}</td>
                        <td><ActiveBadge active={sub.active} /></td>
                        {editable && (
                          <td className="actions-col">
                            <button type="button" className="btn btn-small" onClick={() => setForm({ kind: 'sub', entity, record: sub })}>Edit</button>
                            <button type="button" className="btn btn-small btn-danger-text"
                                    onClick={() => setDeleting({ kind: 'sub', entity, record: sub })}>Delete</button>
                          </td>
                        )}
                      </tr>
                    ))}
                  </tbody>
                </table>
              ) : <p className="muted small">No sub-entities.</p>}
            </section>
          ))}
          <Pagination page={page} totalPages={list.data.totalPages} onChange={setPage} />
        </>
      ) : <EmptyState title="No entities found">{editable && 'Create the first entity to start raising purchase requests.'}</EmptyState>}

      {form && (
        <RecordForm singular={form.kind === 'entity' ? 'Entity' : `Sub-entity of ${form.entity.name}`}
                    fields={form.kind === 'entity' ? ENTITY_FIELDS : SUB_FIELDS}
                    initial={form.record || {}} isNew={!form.record} onClose={() => setForm(null)} onSave={save} />
      )}
      {deleting && (
        <ConfirmDialog title="Delete" danger busy={busy} confirmLabel="Delete" onCancel={() => setDeleting(null)} onConfirm={remove}
                       message={`Delete "${deleting.record.name}"? Entities with sub-entities, customers or purchase requests cannot be deleted.`} />
      )}
    </div>
  );
}
