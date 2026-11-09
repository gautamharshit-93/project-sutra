import { Navigate, useLocation } from 'react-router-dom';
import { hasRole } from '../utils/permissions.js';
import { useAuth } from './AuthContext.jsx';
import Spinner from '../components/Spinner.jsx';

/**
 * Sends signed-out users to the login page (or, at the site root, to the `landing` page when one is given), and
 * users without one of `roles` to a 403 notice.
 */
export default function RequireAuth({ roles, landing, children }) {
  const { user, checking } = useAuth();
  const location = useLocation();

  if (checking) return <Spinner label="Checking your session" />;
  if (!user && landing && location.pathname === '/') return <Navigate to={landing} replace />;
  if (!user) return <Navigate to="/login" replace state={{ from: location.pathname }} />;
  if (roles && !hasRole(user, ...roles)) {
    return (
      <div className="page">
        <div className="notice notice-error" role="alert">
          <strong>Access denied.</strong> Your role does not allow access to this page.
        </div>
      </div>
    );
  }
  return children;
}
