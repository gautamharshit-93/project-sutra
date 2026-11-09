import { useState } from 'react';
import { errorMessage, fieldErrors } from '../api/client.js';
import { useAuth } from '../auth/AuthContext.jsx';
import { useApi } from '../hooks/useApi.js';
import { can } from '../utils/permissions.js';
import { emptyToNull } from '../utils/format.js';
import { useToast } from './Toast.jsx';
import { ConfirmDialog, EmptyState, ErrorBanner, Field, Modal, Pagination, Spinner } from './ui.jsx';

export function ActiveBadge({ active }) {
  return <span className={`badge ${active ? 'badge-success' : 'badge-neutral'}`}>{active ? 'Active' : 'Inactive'}</span>;
}

/**
 * List + search + create/edit/delete for a master-data resource.
 * `fields`: [{ name, label, type?, required?, options?, wide? }] ; `columns`: [{ label, render(row) }]
 */
export default function CrudPage({ title, singular, description, api, fields, columns, toForm, extraActions, allowCreate = true, notice }) {
  const { user } = useAuth();
  const { notify } = useToast();
  const editable = can.manageMasterData(user);
  const [query, setQuery] = useState('');
  const [page, setPage] = useState(0);
  const [editing, setEditing] = useState(null); // null | {} (new) | row
  const [deleting, setDeleting] = useState(null);
  const [busy, setBusy] = useState(false);

  const list = useApi(() => api.list({ q: query || undefined, page, size: 15 }), [query, page]);

  async function remove() {
    setBusy(true);
    try {
      await api.remove(deleting.id);
      notify(`${singular} deleted`);
      setDeleting(null);
      list.reload();
    } catch (err) {
      notify(errorMessage(err), 'error');
      setDeleting(null);
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="page">
      <div className="page-header">
        <div>
          <h1>{title}</h1>
          {description && <p className="muted">{description}</p>}
        </div>
        {editable && allowCreate && <button type="button" className="btn btn-primary" onClick={() => setEditing({})}>New {singular.toLowerCase()}</button>}
      </div>
      {notice && <div className="notice notice-info page-notice">{notice}</div>}

      <div className="toolbar">
        <input type="search" className="search" placeholder={`Search ${title.toLowerCase()} by name or code`}
               aria-label="Search" value={query} onChange={(e) => { setQuery(e.target.value); setPage(0); }} />
      </div>
      <ErrorBanner message={list.error} onRetry={list.reload} />

      {list.loading && !list.data ? <Spinner /> : list.data?.content?.length ? (
        <div className="card card-flush">
          <table className="table">
            <thead>
              <tr>
                {columns.map((c) => <th key={c.label}>{c.label}</th>)}
                <th>Status</th>
                {editable && <th className="actions-col">Actions</th>}
              </tr>
            </thead>
            <tbody>
              {list.data.content.map((row) => (
                <tr key={row.id}>
                  {columns.map((c) => <td key={c.label}>{c.render(row)}</td>)}
                  <td><ActiveBadge active={row.active} /></td>
                  {editable && (
                    <td className="actions-col">
                      {extraActions?.(row, list.reload)}
                      <button type="button" className="btn btn-small" onClick={() => setEditing(row)}>Edit</button>
                      <button type="button" className="btn btn-small btn-danger-text" onClick={() => setDeleting(row)}>Delete</button>
                    </td>
                  )}
                </tr>
              ))}
            </tbody>
          </table>
          <Pagination page={page} totalPages={list.data.totalPages} onChange={setPage} />
        </div>
      ) : (
        <EmptyState title={`No ${title.toLowerCase()} found`}>
          {editable ? `Create the first ${singular.toLowerCase()} with the button above.` : 'Ask an administrator to add them.'}
        </EmptyState>
      )}

      {editing && (
        <RecordForm singular={singular} fields={fields} initial={editing.id ? toForm(editing) : {}}
                    isNew={!editing.id} onClose={() => setEditing(null)}
                    onSave={async (values) => {
                      const body = emptyToNull(values);
                      if (editing.id) await api.update(editing.id, body);
                      else await api.create(body);
                      notify(`${singular} saved`);
                      setEditing(null);
                      list.reload();
                    }} />
      )}
      {deleting && (
        <ConfirmDialog title={`Delete ${singular.toLowerCase()}`} danger busy={busy} confirmLabel="Delete"
                       message={`Delete "${deleting.name}"? Records that are in use cannot be deleted; deactivate them instead.`}
                       onConfirm={remove} onCancel={() => setDeleting(null)} />
      )}
    </div>
  );
}

/** Modal form generated from field definitions. Shows server-side validation errors next to each field. */
export function RecordForm({ singular, fields, initial, isNew, onClose, onSave }) {
  const [values, setValues] = useState(() => {
    const v = { active: true, ...initial };
    for (const f of fields) if (v[f.name] === undefined || v[f.name] === null) v[f.name] = f.type === 'checkbox' ? false : '';
    return v;
  });
  const [errors, setErrors] = useState({});
  const [error, setError] = useState('');
  const [busy, setBusy] = useState(false);

  async function submit(e) {
    e.preventDefault();
    setBusy(true);
    setError('');
    setErrors({});
    try {
      await onSave(values);
    } catch (err) {
      setErrors(fieldErrors(err));
      setError(errorMessage(err));
    } finally {
      setBusy(false);
    }
  }

  const set = (name, value) => setValues((v) => ({ ...v, [name]: value }));
  return (
    <Modal title={`${isNew ? 'New' : 'Edit'} ${singular.toLowerCase()}`} onClose={onClose} footer={(
      <>
        <button type="button" className="btn" onClick={onClose}>Cancel</button>
        <button type="submit" form="record-form" className="btn btn-primary" disabled={busy}>{busy ? 'Saving…' : 'Save'}</button>
      </>
    )}>
      <form id="record-form" className="form-grid" onSubmit={submit} noValidate>
        <ErrorBanner message={error} />
        {fields.map((f) => (
          <Field key={f.name} label={f.label} required={f.required} error={errors[f.name]} hint={f.hint} wide={f.wide}>
            {f.type === 'textarea' ? (
              <textarea rows={3} value={values[f.name]} onChange={(e) => set(f.name, e.target.value)} />
            ) : f.type === 'select' ? (
              <select value={values[f.name] ?? ''} onChange={(e) => set(f.name, e.target.value ? Number(e.target.value) : '')}>
                <option value="">— none —</option>
                {f.options.map((o) => <option key={o.value} value={o.value}>{o.label}</option>)}
              </select>
            ) : f.type === 'checkbox' ? (
              <input type="checkbox" checked={Boolean(values[f.name])} onChange={(e) => set(f.name, e.target.checked)} />
            ) : (
              <input type={f.type || 'text'} value={values[f.name]} required={f.required}
                     onChange={(e) => set(f.name, e.target.value)} />
            )}
          </Field>
        ))}
        <Field label="Active">
          <input type="checkbox" checked={values.active !== false} onChange={(e) => set('active', e.target.checked)} />
        </Field>
      </form>
    </Modal>
  );
}
