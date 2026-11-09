import { useState } from 'react';
import { errorMessage } from '../api/client.js';
import { authApi } from '../api/endpoints.js';
import { useAuth } from '../auth/AuthContext.jsx';
import { useToast } from './Toast.jsx';
import { ErrorBanner, Field, Modal } from './ui.jsx';

/**
 * Changes the signed-in user's password. With `required` (an administrator still on the start-up password) it
 * cannot be dismissed. Changing the password ends every session, so the user is signed in again with the new one.
 */
export default function ChangePasswordModal({ onClose, required = false }) {
  const { notify } = useToast();
  const { user, login } = useAuth();
  const [form, setForm] = useState({ current: '', next: '', confirm: '' });
  const [error, setError] = useState('');
  const [busy, setBusy] = useState(false);

  const mismatch = form.confirm && form.next !== form.confirm;

  async function submit(e) {
    e.preventDefault();
    if (mismatch) return;
    setBusy(true);
    setError('');
    try {
      await authApi.changePassword(form.current, form.next);
      await login(user.username, form.next);
      notify('Password changed');
      onClose();
    } catch (err) {
      setError(errorMessage(err));
    } finally {
      setBusy(false);
    }
  }

  const set = (key) => (e) => setForm((f) => ({ ...f, [key]: e.target.value }));
  return (
    <Modal title={required ? 'Choose a new password' : 'Change password'} onClose={onClose} dismissible={!required} footer={(
      <>
        {!required && <button type="button" className="btn" onClick={onClose}>Cancel</button>}
        <button type="submit" form="password-form" className="btn btn-primary" disabled={busy || mismatch}>
          {busy ? 'Saving…' : 'Change password'}
        </button>
      </>
    )}>
      <form id="password-form" className="form-grid" onSubmit={submit}>
        {required && (
          <div className="notice notice-warning">
            You are still using the start-up password, which anyone reading the setup guide knows. Choose your own
            password to continue.
          </div>
        )}
        <ErrorBanner message={error} />
        <Field label="Current password" required wide>
          <input type="password" autoComplete="current-password" value={form.current} onChange={set('current')} required />
        </Field>
        <Field label="New password" required hint="At least 8 characters with a letter and a digit" wide>
          <input type="password" autoComplete="new-password" minLength={8} value={form.next} onChange={set('next')} required />
        </Field>
        <Field label="Confirm new password" required error={mismatch ? 'Passwords do not match' : ''} wide>
          <input type="password" autoComplete="new-password" value={form.confirm} onChange={set('confirm')} required />
        </Field>
      </form>
    </Modal>
  );
}
