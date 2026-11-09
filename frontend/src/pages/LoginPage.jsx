import { useState } from 'react';
import { Link, Navigate, useLocation, useNavigate } from 'react-router-dom';
import { motion } from 'framer-motion';
import { errorMessage } from '../api/client.js';
import { useAuth } from '../auth/AuthContext.jsx';
import Logo from '../components/Logo.jsx';
import Scene3D from '../components/three/Scene3D.jsx';
import { ErrorBanner, Field } from '../components/ui.jsx';
import { homePath } from '../utils/permissions.js';

export default function LoginPage() {
  const { user, login } = useAuth();
  const navigate = useNavigate();
  const location = useLocation();
  const [username, setUsername] = useState('');
  const [password, setPassword] = useState('');
  const [error, setError] = useState('');
  const [busy, setBusy] = useState(false);

  if (user) return <Navigate to={location.state?.from || homePath(user)} replace />;

  async function submit(e) {
    e.preventDefault();
    setBusy(true);
    setError('');
    try {
      const signedIn = await login(username.trim(), password);
      navigate(location.state?.from || homePath(signedIn), { replace: true });
    } catch (err) {
      setError(errorMessage(err));
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="login-screen">
      <motion.section className="login-showcase" initial={{ opacity: 0, x: -30 }} animate={{ opacity: 1, x: 0 }}
                      transition={{ duration: 0.7, ease: [0.22, 1, 0.36, 1] }}>
        <Logo size={44} wordmark subtitle="Vendor management" />
        <Scene3D compact className="login-scene" />
        <div className="login-pitch">
          <h1>Every vendor thread, woven into one decision.</h1>
          <p>Collect quotations, let AI read them, compare like for like, and award with a full compliance trail.</p>
          <ul className="login-points">
            <li>PDF, Excel and Word quotations read automatically</li>
            <li>Totals recalculated by the system, never by AI</li>
            <li>Every award recorded with who, when and why</li>
          </ul>
        </div>
      </motion.section>

      <motion.form className="login-card" onSubmit={submit} aria-label="Sign in"
                   initial={{ opacity: 0, y: 24 }} animate={{ opacity: 1, y: 0 }} transition={{ duration: 0.6, delay: 0.15 }}>
        <div className="brand-login">
          <Logo size={46} wordmark subtitle="Automotive parts procurement" dark={false} />
        </div>
        <div>
          <h2 className="login-title">Sign in</h2>
          <p className="muted small">
            Company users run RFQs, compare vendor quotations and award orders. Suppliers sign in to see open RFQs and
            send their quotations.
          </p>
        </div>
        <ErrorBanner message={error} />
        <Field label="Username" required wide>
          <input autoComplete="username" value={username} onChange={(e) => setUsername(e.target.value)} required autoFocus />
        </Field>
        <Field label="Password" required wide>
          <input type="password" autoComplete="current-password" value={password}
                 onChange={(e) => setPassword(e.target.value)} required />
        </Field>
        <button type="submit" className="btn btn-primary btn-block" disabled={busy}>
          {busy ? 'Signing in…' : 'Sign in'}
        </button>
        <p className="signup-link small">Supplier without a login? <Link to="/register">Register your company</Link></p>
        <Link to="/welcome" className="login-home small">← Back to the SUTRA home page</Link>
      </motion.form>
    </div>
  );
}
