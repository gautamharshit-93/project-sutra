import { useEffect, useRef, useState } from 'react';
import { NavLink, Outlet, useLocation } from 'react-router-dom';
import { AnimatePresence, motion } from 'framer-motion';
import { useAuth } from '../auth/AuthContext.jsx';
import { useBadges } from '../hooks/useBadges.js';
import { can, homePath, roleLabel } from '../utils/permissions.js';
import { AssistantButton, AssistantHelpCard, AssistantProvider } from './Assistant.jsx';
import BackButton from './BackButton.jsx';
import ChangePasswordModal from './ChangePasswordModal.jsx';
import GlobalSearch from './GlobalSearch.jsx';
import Icon from './Icon.jsx';
import Logo from './Logo.jsx';

/**
 * Menu entries for the signed-in user's roles: each role sees only what it can use. Grouped as Contracts (RFQs),
 * Vendors, Compliance (audit trail) and Reports (award register).
 */
export function navFor(user) {
  if (can.useVendorPortal(user)) {
    return [
      { section: 'Vendor portal' },
      { to: '/portal', label: 'Open RFQs', icon: 'inbox', end: true },
      { to: '/portal/quotations', label: 'My quotations', icon: 'file' },
      { to: '/portal/profile', label: 'Company profile', icon: 'vendors' },
    ];
  }
  const items = [{ section: 'Overview' }, { to: '/', label: 'Dashboard', icon: 'dashboard', end: true }];
  // Entities (who is buying) come right above RFQs: an RFQ always belongs to one
  if (can.workOnRfqs(user)) {
    items.push({ section: 'Contracts' });
    if (can.manageMasterData(user)) items.push({ to: '/entities', label: 'Entities', icon: 'entities' });
    items.push({ to: '/purchase-requests', label: 'RFQs', icon: 'contracts' });
  }
  if (can.viewVendors(user)) items.push({ section: 'Vendors' }, { to: '/vendors', label: 'Vendors', icon: 'vendors' });
  if (can.manageMasterData(user)) items.push({ to: '/customers', label: 'Customers', icon: 'customers' });
  if (can.viewAudit(user)) items.push({ section: 'Compliance' }, { to: '/audit', label: 'Audit log', icon: 'compliance' });
  if (can.viewAwardRegister(user)) items.push({ section: 'Reports' }, { to: '/awards', label: 'Award register', icon: 'reports' });
  if (can.manageUsers(user)) items.push({ section: 'Administration' }, { to: '/users', label: 'Users & roles', icon: 'users' });
  return items;
}

function initials(name = '') {
  return name.split(/\s+/).filter(Boolean).slice(0, 2).map((w) => w[0].toUpperCase()).join('');
}

function UserMenu({ user, onPassword, onLogout }) {
  const [open, setOpen] = useState(false);
  const ref = useRef(null);
  useEffect(() => {
    if (!open) return undefined;
    const close = (e) => !ref.current?.contains(e.target) && setOpen(false);
    const esc = (e) => e.key === 'Escape' && setOpen(false);
    document.addEventListener('mousedown', close);
    document.addEventListener('keydown', esc);
    return () => { document.removeEventListener('mousedown', close); document.removeEventListener('keydown', esc); };
  }, [open]);

  return (
    <div className="user-menu" ref={ref}>
      <button type="button" className="user-trigger" aria-haspopup="menu" aria-expanded={open} onClick={() => setOpen((o) => !o)}>
        <span className="avatar">{initials(user.fullName)}</span>
        <span className="user-chip">
          <span className="user-name">{user.fullName}</span>
          <span className="user-roles">
            {user.roles.map(roleLabel).join(', ')}{user.vendorName ? ` · ${user.vendorName}` : ''}
          </span>
        </span>
        <Icon name="chevron" size={16} className={`user-caret${open ? ' user-caret-open' : ''}`} />
      </button>
      <AnimatePresence>
        {open && (
          <motion.div className="user-dropdown" role="menu" initial={{ opacity: 0, y: -8 }} animate={{ opacity: 1, y: 0 }}
                      exit={{ opacity: 0, y: -8 }} transition={{ duration: 0.15 }}>
            <div className="user-dropdown-head">
              <strong>{user.fullName}</strong>
              <span className="muted small">@{user.username}</span>
            </div>
            <button type="button" role="menuitem" onClick={() => { setOpen(false); onPassword(); }}>
              <Icon name="key" size={16} /> Change password
            </button>
            <button type="button" role="menuitem" className="danger" onClick={onLogout}>
              <Icon name="logout" size={16} /> Sign out
            </button>
          </motion.div>
        )}
      </AnimatePresence>
    </div>
  );
}

export default function Layout() {
  const { user, logout } = useAuth();
  const location = useLocation();
  const [changingPassword, setChangingPassword] = useState(false);
  const [menuOpen, setMenuOpen] = useState(false);
  const atHome = location.pathname === homePath(user);
  const nav = navFor(user);
  const pages = nav.filter((i) => i.to);
  const badges = useBadges(user, location.pathname);

  return (
    <AssistantProvider enabled={can.isStaff(user)} canDraft={can.createRequest(user)}>
    <div className={`shell${menuOpen ? ' shell-menu-open' : ''}`}>
      <aside className="sidebar">
        <Logo wordmark subtitle={can.useVendorPortal(user) ? 'Supplier portal' : 'Vendor management'} />
        <nav className="nav" onClick={() => setMenuOpen(false)}>
          {nav.map((item) => item.section
            ? <div key={item.section} className="nav-section">{item.section}</div>
            : (
              <NavLink key={item.to} to={item.to} end={item.end}
                       className={({ isActive }) => `nav-link${isActive ? ' nav-link-active' : ''}`}>
                {({ isActive }) => (
                  <>
                    {isActive && <motion.span layoutId="nav-pill" className="nav-pill" transition={{ type: 'spring', stiffness: 420, damping: 34 }} />}
                    <Icon name={item.icon} size={18} className="nav-icon" />
                    <span className="nav-label">{item.label}</span>
                    {badges[item.to] > 0 && <span className="nav-badge" aria-label={`${badges[item.to]} waiting`}>{badges[item.to]}</span>}
                  </>
                )}
              </NavLink>
            ))}
        </nav>
        <AssistantHelpCard />
        <div className="sidebar-foot">
          <span className="dot dot-on" /> Signed in as {roleLabel(user.roles[0])}
        </div>
      </aside>
      <div className="sidebar-scrim" onClick={() => setMenuOpen(false)} />

      <div className="main">
        <header className="topbar">
          <button type="button" className="icon-btn menu-toggle" aria-label="Menu" onClick={() => setMenuOpen((o) => !o)}>
            <Icon name="menu" size={20} />
          </button>
          <GlobalSearch user={user} pages={pages} />
          <div className="topbar-spacer" />
          <AssistantButton />
          <UserMenu user={user} onPassword={() => setChangingPassword(true)} onLogout={logout} />
        </header>
        <main className="content">
          {!atHome && <BackButton />}
          <motion.div key={location.pathname} className="route-view" initial={{ opacity: 0, y: 14 }} animate={{ opacity: 1, y: 0 }}
                      transition={{ duration: 0.35, ease: [0.22, 1, 0.36, 1] }}>
            <Outlet />
          </motion.div>
        </main>
      </div>

      {(changingPassword || user.passwordChangeRequired) && (
        <ChangePasswordModal required={user.passwordChangeRequired} onClose={() => setChangingPassword(false)} />
      )}
    </div>
    </AssistantProvider>
  );
}
