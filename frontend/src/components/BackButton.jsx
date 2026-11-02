import { useLocation, useNavigate } from 'react-router-dom';

/**
 * "Back" to the previous screen. When the page was opened directly (no history inside the app), it goes one level
 * up instead, e.g. /quotations/12 -> /purchase-requests or /portal/rfqs/4 -> /portal.
 */
export default function BackButton({ fallback, label = 'Back' }) {
  const navigate = useNavigate();
  const location = useLocation();

  function goBack() {
    const inAppHistory = (window.history.state?.idx ?? 0) > 0;
    if (inAppHistory) {
      navigate(-1);
    } else {
      navigate(fallback || parentOf(location.pathname));
    }
  }

  return <button type="button" className="back-link" onClick={goBack}>{label}</button>;
}

export function parentOf(path) {
  const parts = path.split('/').filter(Boolean);
  if (parts[0] === 'quotations') return '/purchase-requests';
  if (parts[0] === 'portal') return '/portal';
  parts.pop();
  return `/${parts.join('/')}`;
}
