import { useState } from 'react';
import { errorMessage, fieldErrors } from '../api/client.js';
import { usersApi, vendorsApi } from '../api/endpoints.js';
import { ActiveBadge } from '../components/CrudPage.jsx';
import { useToast } from '../components/Toast.jsx';
import { ConfirmDialog, ErrorBanner, Field, Modal, Pagination, Spinner } from '../components/ui.jsx';
import { useApi } from '../hooks/useApi.js';
import { formatDate } from '../utils/format.js';
import { roleLabel } from '../utils/permissions.js';

export default function UsersPage() {
  const { notify } = useToast();
  const [query, setQuery] = useState('');
  const [page, setPage] = useState(0);
  const [editing, setEditing] = useState(null);
  const [deleting, setDeleting] = useState(null);
  const users = useApi(() => usersApi.list({ q: query || undefined, page, size: 20 }), [query, page]);
  const roles = useApi(() => usersApi.roles(), []);
  const signups = useApi(() => usersApi.vendorSignups(), []);
  const [deciding, setDeciding] = useState(null);

  async function decide(signup, approve) {
    setDeciding(signup.userId);
    try {
      if (approve) {
        const user = await usersApi.approveVendor(signup.userId);
        notify(`${signup.companyName} approved: ${user.username} can now sign in`);
      } else {
        await usersApi.rejectVendor(signup.userId);
        notify(`Sign-up from ${signup.companyName} rejected`);
      }
      signups.reload({ silent: true });
      users.reload({ silent: true });
    } catch (err) {
      notify(errorMessage(err), 'error');
    } finally {
      setDeciding(null);
    }
  }

  async function remove() {
    try {
      await usersApi.remove(deleting.id);
      notify('User deleted');
      users.reload();
    } catch (err) {
      notify(errorMessage(err), 'error');
    } finally {
      setDeleting(null);
    }
  }

  return (
    <div className="page">
      <div className="page-header">
        <div>
          <h1>Users &amp; roles</h1>
          <p className="muted">
            Company logins (administrator, buyer, manager) and vendor logins. A vendor login belongs to one
            vendor and only sees open RFQs and its own quotations.
          </p>
        </div>
        <button type="button" className="btn btn-primary" onClick={() => setEditing({})}>New user</button>
      </div>

      {signups.data?.length > 0 && (
        <div className="card signups">
          <h2>Vendor sign-ups waiting for approval <span className="tab-count">{signups.data.length}</span></h2>
          <p className="muted small">Approving creates the vendor and activates its login in one step.</p>
          <ul className="signup-list">
            {signups.data.map((s) => (
              <li key={s.userId}>
                <div>
                  <strong>{s.companyName}</strong>
                  <span className="muted small">
                    {[s.contactPerson, s.email, s.phone, s.taxNumber && `GST ${s.taxNumber}`].filter(Boolean).join(' · ')}
                  </span>
                  <span className="muted small">Login <code>{s.username}</code> · signed up {formatDate(s.createdAt)}</span>
                </div>
                <div className="signup-actions">
                  <button type="button" className="btn btn-small btn-danger-text" disabled={deciding === s.userId}
                          onClick={() => decide(s, false)}>Reject</button>
                  <button type="button" className="btn btn-small btn-primary" disabled={deciding === s.userId}
                          onClick={() => decide(s, true)}>{deciding === s.userId ? 'Working…' : 'Approve'}</button>
                </div>
              </li>
            ))}
          </ul>
        </div>
      )}

      {roles.data && (
        <div className="role-legend">
          {roles.data.map((r) => (
            <div key={r.name} className="role-card"><strong>{roleLabel(r.name)}</strong><span>{r.description}</span></div>
          ))}
        </div>
      )}

      <div className="toolbar">
        <input type="search" className="search" placeholder="Search users" aria-label="Search"
               value={query} onChange={(e) => { setQuery(e.target.value); setPage(0); }} />
      </div>
      <ErrorBanner message={users.error} onRetry={users.reload} />
      {users.loading && !users.data ? <Spinner /> : (
        <div className="card card-flush">
          <table className="table">
            <thead><tr><th>Username</th><th>Name</th><th>Email</th><th>Roles</th><th>Vendor</th><th>Status</th><th>Created</th><th className="actions-col" /></tr></thead>
            <tbody>
              {users.data?.content.map((u) => (
                <tr key={u.id}>
                  <td><code>{u.username}</code></td>
                  <td>{u.fullName}</td>
                  <td>{u.email}</td>
                  <td>{u.roles.map(roleLabel).join(', ')}</td>
                  <td>{u.vendorName || '—'}</td>
                  <td>{u.pendingApproval ? <span className="badge badge-warning">Awaiting approval</span> : <ActiveBadge active={u.active} />}</td>
                  <td>{formatDate(u.createdAt)}</td>
                  <td className="actions-col">
                    <button type="button" className="btn btn-small" onClick={() => setEditing(u)}>Edit</button>
                    <button type="button" className="btn btn-small btn-danger-text" onClick={() => setDeleting(u)}>Delete</button>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
          <Pagination page={page} totalPages={users.data?.totalPages} onChange={setPage} />
        </div>
      )}

      {editing && (
        <UserForm user={editing} roles={roles.data || []} onClose={() => setEditing(null)}
                  onSaved={() => { setEditing(null); notify('User saved'); users.reload(); }} />
      )}
      {deleting && (
        <ConfirmDialog title="Delete user" danger confirmLabel="Delete" onCancel={() => setDeleting(null)} onConfirm={remove}
                       message={`Delete ${deleting.username}? Users who own requests or quotations must be deactivated instead.`} />
      )}
    </div>
  );
}

function UserForm({ user, roles, onClose, onSaved }) {
  const isNew = !user.id;
  const [values, setValues] = useState({
    username: user.username || '', email: user.email || '', fullName: user.fullName || '',
    password: '', roles: user.roles || ['PROCUREMENT'], active: user.active !== false, vendorId: user.vendorId || '',
  });
  const vendors = useApi(() => vendorsApi.list({ size: 200 }), []);
  const isVendorLogin = values.roles.includes('VENDOR');
  const [errors, setErrors] = useState({});
  const [error, setError] = useState('');
  const [busy, setBusy] = useState(false);

  // A vendor login has the VENDOR role only; choosing it clears company roles and the other way round.
  const toggleRole = (name) => setValues((v) => {
    if (v.roles.includes(name)) return { ...v, roles: v.roles.filter((r) => r !== name) };
    if (name === 'VENDOR') return { ...v, roles: ['VENDOR'] };
    return { ...v, roles: [...v.roles.filter((r) => r !== 'VENDOR'), name], vendorId: '' };
  });

  async function submit(e) {
    e.preventDefault();
    setBusy(true);
    setError('');
    setErrors({});
    const body = { ...values, password: values.password || null, vendorId: isVendorLogin && values.vendorId ? Number(values.vendorId) : null };
    try {
      if (isNew) await usersApi.create(body);
      else await usersApi.update(user.id, body);
      onSaved();
    } catch (err) {
      setErrors(fieldErrors(err));
      setError(errorMessage(err));
    } finally {
      setBusy(false);
    }
  }

  const set = (key) => (e) => setValues((v) => ({ ...v, [key]: e.target.value }));
  return (
    <Modal title={isNew ? 'New user' : `Edit ${user.username}`} onClose={onClose} footer={(
      <>
        <button type="button" className="btn" onClick={onClose}>Cancel</button>
        <button type="submit" form="user-form" className="btn btn-primary" disabled={busy || !values.roles.length || (isVendorLogin && !values.vendorId)}>
          {busy ? 'Saving…' : 'Save'}
        </button>
      </>
    )}>
      <form id="user-form" className="form-grid" onSubmit={submit} noValidate>
        <ErrorBanner message={error} />
        <Field label="Username" required error={errors.username}>
          <input value={values.username} onChange={set('username')} disabled={!isNew} autoComplete="off" />
        </Field>
        <Field label="Full name" required error={errors.fullName}>
          <input value={values.fullName} onChange={set('fullName')} />
        </Field>
        <Field label="Email" required error={errors.email}>
          <input type="email" value={values.email} onChange={set('email')} />
        </Field>
        <Field label={isNew ? 'Password' : 'New password'} required={isNew} error={errors.password}
               hint={isNew ? 'At least 8 characters with a letter and a digit' : 'Leave blank to keep the current password'}>
          <input type="password" value={values.password} onChange={set('password')} autoComplete="new-password" />
        </Field>
        <fieldset className="field field-wide">
          <legend className="field-label">Roles <span className="required">*</span></legend>
          <div className="checkbox-row">
            {roles.map((r) => (
              <label key={r.name} className="checkbox">
                <input type="checkbox" checked={values.roles.includes(r.name)} onChange={() => toggleRole(r.name)} />
                {roleLabel(r.name)}
              </label>
            ))}
          </div>
          {errors.roles && <span className="field-error">{errors.roles}</span>}
        </fieldset>
        {isVendorLogin && (
          <Field label="Vendor" required wide error={errors.vendorId}
                 hint="The supplier this login belongs to. Add vendors first under Master data → Vendors.">
            <select value={values.vendorId} onChange={set('vendorId')}>
              <option value="">Select vendor</option>
              {(vendors.data?.content || []).filter((v) => v.active || v.id === user.vendorId)
                .map((v) => <option key={v.id} value={v.id}>{v.name}</option>)}
            </select>
          </Field>
        )}
        <Field label="Active">
          <input type="checkbox" checked={values.active} onChange={(e) => setValues((v) => ({ ...v, active: e.target.checked }))} />
        </Field>
      </form>
    </Modal>
  );
}
