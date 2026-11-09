import { useState } from 'react';
import { Link } from 'react-router-dom';
import { motion } from 'framer-motion';
import { errorMessage, fieldErrors } from '../api/client.js';
import { authApi } from '../api/endpoints.js';
import Logo from '../components/Logo.jsx';
import Icon from '../components/Icon.jsx';
import { ErrorBanner, Field } from '../components/ui.jsx';

const EMPTY = {
  companyName: '', contactPerson: '', email: '', phone: '', taxNumber: '', address: '', website: '',
  username: '', password: '', confirm: '',
};

/**
 * Suppliers register their company and login themselves. The account waits for one-click approval by the SUTRA
 * administrator; until then it cannot sign in or see any RFQ.
 */
export default function VendorSignupPage() {
  const [values, setValues] = useState(EMPTY);
  const [errors, setErrors] = useState({});
  const [error, setError] = useState('');
  const [busy, setBusy] = useState(false);
  const [done, setDone] = useState(null);

  const set = (key) => (e) => setValues((v) => ({ ...v, [key]: e.target.value }));

  async function submit(e) {
    e.preventDefault();
    setErrors({});
    setError('');
    if (values.password !== values.confirm) {
      setErrors({ confirm: 'The passwords do not match' });
      return;
    }
    setBusy(true);
    try {
      const { confirm, ...body } = values;
      Object.keys(body).forEach((k) => { if (body[k] === '') body[k] = null; });
      setDone(await authApi.registerVendor(body));
    } catch (err) {
      setErrors(fieldErrors(err));
      setError(errorMessage(err));
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="login-screen">
      <motion.section className="login-showcase" initial={{ opacity: 0, x: -30 }} animate={{ opacity: 1, x: 0 }}
                      transition={{ duration: 0.7, ease: [0.22, 1, 0.36, 1] }}>
        <Logo size={44} wordmark subtitle="Vendor portal" />
        <div className="login-pitch">
          <h1>Quote on RFQs from one place.</h1>
          <p>Register your company once. After approval you see open RFQs, send quotations as PDF, Excel or Word,
            and follow the outcome.</p>
          <ul className="login-points">
            <li>Keep your contact and GST details up to date yourself</li>
            <li>Only you see your quotations</li>
            <li>One login per company</li>
          </ul>
        </div>
      </motion.section>

      {done ? (
        <motion.div className="login-card" initial={{ opacity: 0, y: 24 }} animate={{ opacity: 1, y: 0 }}>
          <span className="signup-done-icon"><Icon name="check" size={28} /></span>
          <h2 className="login-title">Registration received</h2>
          <p>{done.message}</p>
          <p className="muted small">Your username is <code>{done.username}</code>.</p>
          <Link to="/login" className="btn btn-primary btn-block">Go to sign in</Link>
        </motion.div>
      ) : (
        <motion.form className="login-card signup-card" onSubmit={submit} aria-label="Register your company" noValidate
                     initial={{ opacity: 0, y: 24 }} animate={{ opacity: 1, y: 0 }} transition={{ duration: 0.6, delay: 0.15 }}>
          <div>
            <h2 className="login-title">Register your company</h2>
            <p className="muted small">For suppliers. Your account is activated once SUTRA&apos;s administrator approves it.</p>
          </div>
          <ErrorBanner message={error} />
          <div className="form-grid">
            <Field label="Company name" required wide error={errors.companyName}>
              <input value={values.companyName} onChange={set('companyName')} autoFocus />
            </Field>
            <Field label="Contact person" required error={errors.contactPerson}>
              <input value={values.contactPerson} onChange={set('contactPerson')} autoComplete="name" />
            </Field>
            <Field label="Email" required error={errors.email}>
              <input type="email" value={values.email} onChange={set('email')} autoComplete="email" />
            </Field>
            <Field label="Phone" error={errors.phone}>
              <input value={values.phone} onChange={set('phone')} autoComplete="tel" />
            </Field>
            <Field label="GST / tax number" error={errors.taxNumber}>
              <input value={values.taxNumber} onChange={set('taxNumber')} />
            </Field>
            <Field label="Address" wide error={errors.address}>
              <textarea rows={2} value={values.address} onChange={set('address')} />
            </Field>
            <Field label="Username" required error={errors.username} hint="3-60 letters, digits, dot, dash or underscore">
              <input value={values.username} onChange={set('username')} autoComplete="username" />
            </Field>
            <Field label="Website" error={errors.website}>
              <input value={values.website} onChange={set('website')} />
            </Field>
            <Field label="Password" required error={errors.password} hint="At least 8 characters, a letter and a digit">
              <input type="password" value={values.password} onChange={set('password')} autoComplete="new-password" />
            </Field>
            <Field label="Confirm password" required error={errors.confirm}>
              <input type="password" value={values.confirm} onChange={set('confirm')} autoComplete="new-password" />
            </Field>
          </div>
          <button type="submit" className="btn btn-primary btn-block" disabled={busy}>
            {busy ? 'Registering…' : 'Register'}
          </button>
          <Link to="/login" className="login-home small">Already registered? Sign in</Link>
        </motion.form>
      )}
    </div>
  );
}
