import { useEffect, useRef } from 'react';
import { Link } from 'react-router-dom';
import { motion } from 'framer-motion';
import gsap from 'gsap';
import { extractionApi, purchaseRequestsApi, reportingApi } from '../api/endpoints.js';
import { useAuth } from '../auth/AuthContext.jsx';
import { useApi } from '../hooks/useApi.js';
import Icon from '../components/Icon.jsx';
import Scene3D from '../components/three/Scene3D.jsx';
import { EmptyState, ErrorBanner, Spinner, StatusBadge } from '../components/ui.jsx';
import { formatDate, formatDateTime, humanize } from '../utils/format.js';
import { can, roleLabel } from '../utils/permissions.js';

const CLOSED = ['APPROVED', 'REJECTED', 'CANCELLED'];
const EXPIRY_WINDOW_DAYS = 14;
const DAY = 24 * 60 * 60 * 1000;

const container = { hidden: {}, show: { transition: { staggerChildren: 0.07 } } };
const rise = { hidden: { opacity: 0, y: 18 }, show: { opacity: 1, y: 0, transition: { duration: 0.5, ease: [0.22, 1, 0.36, 1] } } };

function daysUntil(date) {
  return Math.ceil((new Date(`${date}T00:00:00`).getTime() - Date.now()) / DAY);
}

/** Counts and lists come straight from the services; nothing on this page is sample data. */
export default function DashboardPage() {
  const { user } = useAuth();
  const summary = useApi(() => reportingApi.dashboard(), []);
  const rfqs = useApi(() => purchaseRequestsApi.list({ size: 50 }), []);
  const engines = useApi(() => extractionApi.engines(), []);
  const services = useApi(() => reportingApi.services(), []);
  const activity = useApi(() => (can.viewAudit(user) ? reportingApi.auditLogs({ size: 8 }) : Promise.resolve(null)), [user]);
  // Validity dates live on the quotations: read them for the RFQs still in play (at most 8 requests).
  const live = (rfqs.data?.content || []).filter((r) => r.status === 'OPEN' || r.status === 'UNDER_REVIEW').slice(0, 8);
  const liveKey = live.map((r) => r.id).join(',');
  const quotations = useApi(async () => {
    const lists = await Promise.all(live.map((r) => purchaseRequestsApi.quotations(r.id).catch(() => [])));
    return lists.flat();
  }, [liveKey]);

  if (summary.loading && !summary.data) return <Spinner label="Loading dashboard" />;
  const d = summary.data;
  const pr = d?.purchaseRequestsByStatus || {};
  const qs = d?.quotationsByStatus || {};
  const firstName = user.fullName.split(' ')[0];
  const allRfqs = rfqs.data?.content || [];

  const expiring = (quotations.data || [])
    .filter((q) => q.validUntil && q.status !== 'FAILED' && daysUntil(q.validUntil) <= EXPIRY_WINDOW_DAYS)
    .map((q) => ({ ...q, days: daysUntil(q.validUntil) }))
    .sort((a, b) => a.days - b.days);
  const overdue = allRfqs.filter((r) => r.requiredBy && !CLOSED.includes(r.status) && daysUntil(r.requiredBy) < 0);
  const awaitingAward = allRfqs.filter((r) => r.status === 'UNDER_REVIEW');
  const risks = buildRisks({ qs, expiring, overdue, engines: engines.data, services: services.data });

  return (
    <motion.div className="page dashboard" variants={container} initial="hidden" animate="show">
      <motion.section variants={rise} className="dash-hero">
        <div className="dash-hero-text">
          <span className="eyebrow">{new Date().toLocaleDateString('en-IN', { weekday: 'long', day: 'numeric', month: 'long' })}</span>
          <h1>Welcome back, {firstName}</h1>
          <p>{user.roles.map(roleLabel).join(', ')} · {intro(user)}</p>
          <div className="btn-row">
            {can.createRequest(user) && <Link to="/purchase-requests?new=1" className="btn btn-primary"><Icon name="plus" size={16} /> New RFQ</Link>}
            {can.viewAwardRegister(user) && <Link to="/awards" className="btn btn-ghost">Award register <Icon name="arrow" size={16} /></Link>}
            {!can.viewAwardRegister(user) && can.workOnRfqs(user) && <Link to="/purchase-requests" className="btn btn-ghost">All RFQs <Icon name="arrow" size={16} /></Link>}
          </div>
        </div>
        <Scene3D className="dash-hero-scene" />
      </motion.section>

      <ErrorBanner message={summary.error} onRetry={summary.reload} />
      {d && can.manageUsers(user) && <GettingStarted d={d} />}

      {d && (
        <motion.div className="kpis" variants={container}>
          <Kpi icon="vendors" label="Total vendors" value={d.vendors} to={can.viewVendors(user) ? '/vendors' : null}
               sub={d.customers != null ? `${d.customers} customers · ${d.entities} entities` : 'Master data service unavailable'} />
          <Kpi icon="contracts" label="Active RFQs" value={pr.OPEN || 0} to={can.workOnRfqs(user) ? '/purchase-requests' : null}
               sub={`${pr.DRAFT || 0} in draft`} />
          <Kpi icon="clock" label="Pending" value={(qs.NEEDS_REVIEW || 0) + (pr.UNDER_REVIEW || 0)} tone="warm"
               sub={`${qs.NEEDS_REVIEW || 0} quotations to review · ${pr.UNDER_REVIEW || 0} awaiting award`} />
          <Kpi icon="award" label="Awarded contracts" value={pr.APPROVED || 0} to={can.viewAwardRegister(user) ? '/awards' : null}
               sub={`of ${sum(pr)} RFQs in total`} />
        </motion.div>
      )}

      <div className="alerts">
        <motion.section variants={rise} className={`alert-card${expiring.length ? ' alert-card-hot' : ''}`}>
          <header>
            <span className="alert-icon"><Icon name="clock" /></span>
            <div>
              <h2>Expiring quotation validity</h2>
              <p className="muted small">Offers on live RFQs that lapse within {EXPIRY_WINDOW_DAYS} days</p>
            </div>
            <span className="alert-count">{quotations.loading && !quotations.data ? '…' : expiring.length}</span>
          </header>
          {expiring.length ? (
            <ul className="alert-list">
              {expiring.slice(0, 4).map((q) => (
                <li key={q.id}>
                  <Link to={`/quotations/${q.id}`}>{q.vendorName}</Link>
                  <span className="muted small">{q.requestNumber}</span>
                  <span className={`pill ${q.days < 0 ? 'pill-hot' : ''}`}>
                    {q.days < 0 ? `expired ${-q.days}d ago` : q.days === 0 ? 'expires today' : `${q.days}d left`}
                  </span>
                </li>
              ))}
            </ul>
          ) : <p className="alert-ok"><Icon name="check" size={16} /> No offers about to lapse.</p>}
        </motion.section>

        <motion.section variants={rise} className={`alert-card${awaitingAward.length || overdue.length ? ' alert-card-hot' : ''}`}>
          <header>
            <span className="alert-icon"><Icon name="alert" /></span>
            <div>
              <h2>Outstanding decisions</h2>
              <p className="muted small">Compared RFQs awaiting award, and RFQs past their required-by date</p>
            </div>
            <span className="alert-count">{awaitingAward.length + overdue.length}</span>
          </header>
          {awaitingAward.length || overdue.length ? (
            <ul className="alert-list">
              {[...overdue.map((r) => ({ r, overdue: true })), ...awaitingAward.filter((r) => !overdue.includes(r)).map((r) => ({ r }))]
                .slice(0, 4).map(({ r, overdue: late }) => (
                  <li key={r.id}>
                    <Link to={`/purchase-requests/${r.id}`}>{r.title}</Link>
                    <span className="muted small">{r.requestNumber}</span>
                    <span className={`pill ${late ? 'pill-hot' : ''}`}>
                      {late ? `${-daysUntil(r.requiredBy)}d overdue` : 'awaiting award'}
                    </span>
                  </li>
                ))}
            </ul>
          ) : <p className="alert-ok"><Icon name="check" size={16} /> Nothing waiting on a decision.</p>}
        </motion.section>
      </div>

      <div className="dash-grid">
        <motion.section variants={rise} className="card dash-risks">
          <div className="card-title"><Icon name="bell" /><h2>Risk notifications</h2></div>
          {risks.length ? (
            <ul className="risk-list">
              {risks.map((r) => (
                <li key={r.key} className={`risk risk-${r.level}`}>
                  <span className="risk-dot" />
                  <div>
                    <strong>{r.title}</strong>
                    <p className="muted small">{r.text}</p>
                  </div>
                  {r.to && <Link to={r.to} className="risk-go" aria-label={`Open ${r.title}`}><Icon name="arrow" size={16} /></Link>}
                </li>
              ))}
            </ul>
          ) : <EmptyState title="All clear">No open risks right now.</EmptyState>}
        </motion.section>

        <motion.section variants={rise} className="card">
          <div className="card-title"><Icon name="vendors" /><h2>Vendor quotation status</h2></div>
          <Donut counts={qs} />
        </motion.section>

        <motion.section variants={rise} className="card">
          <div className="card-title"><Icon name="pulse" /><h2>Quick actions</h2></div>
          <QuickActions user={user} />
        </motion.section>
      </div>

      <div className="dash-grid dash-grid-wide">
        <motion.section variants={rise} className="card">
          <div className="card-title">
            <Icon name="pulse" /><h2>Recent activity</h2>
            {can.viewAudit(user) && <Link to="/audit" className="card-link">Audit log <Icon name="arrow" size={14} /></Link>}
          </div>
          <Activity user={user} activity={activity} rfqs={allRfqs} />
        </motion.section>

        <div className="dash-stack">
          <motion.section variants={rise} className="card">
            <div className="card-title"><Icon name="reports" /><h2>RFQ pipeline</h2></div>
            <StatusBars counts={pr} />
          </motion.section>
          <motion.section variants={rise} className="card">
            <div className="card-title"><Icon name="server" /><h2>System health</h2></div>
            <Health engines={engines} services={services} d={d} />
          </motion.section>
        </div>
      </div>
    </motion.div>
  );
}

function sum(counts) {
  return Object.values(counts || {}).reduce((a, b) => a + b, 0);
}

function intro(user) {
  if (can.manageUsers(user)) return 'set up users, vendors and master data, and oversee every RFQ.';
  if (can.createRequest(user)) return 'create RFQs, review the quotations vendors send, and compare them.';
  if (can.workOnRfqs(user)) return 'review comparisons and award RFQs to a vendor.';
  return 'read-only view of RFQs, awards and the audit log.';
}

function buildRisks({ qs, expiring, overdue, engines, services }) {
  const risks = [];
  if (qs.FAILED) {
    risks.push({ key: 'failed', level: 'high', title: `${qs.FAILED} extraction(s) failed`,
      text: 'Retry with another engine or enter the quotation by hand.', to: '/purchase-requests' });
  }
  const expired = expiring.filter((q) => q.days < 0);
  if (expired.length) {
    risks.push({ key: 'expired', level: 'high', title: `${expired.length} quotation(s) past validity`,
      text: 'Ask the vendor to extend the offer before awarding.', to: `/quotations/${expired[0].id}` });
  }
  if (overdue.length) {
    risks.push({ key: 'overdue', level: 'high', title: `${overdue.length} RFQ(s) past required-by date`,
      text: `Oldest: ${overdue[0].requestNumber}, due ${formatDate(overdue[0].requiredBy)}.`, to: `/purchase-requests/${overdue[0].id}` });
  }
  if (qs.NEEDS_REVIEW) {
    risks.push({ key: 'review', level: 'medium', title: `${qs.NEEDS_REVIEW} quotation(s) flagged for review`,
      text: 'Uncertain values or missing terms need a reviewer before comparison.', to: '/purchase-requests' });
  }
  const down = (services || []).filter((s) => !s.up);
  if (down.length) {
    risks.push({ key: 'services', level: 'high', title: `${down.length} service(s) down`, text: down.map((s) => humanize(s.service)).join(', ') });
  }
  const groq = engines?.engines?.find((e) => e.engine === 'GROQ');
  if (groq && !groq.available) {
    risks.push({ key: 'groq', level: 'low', title: 'AI extraction not configured',
      text: 'Quotations are read by the rule-based reader. Set GROQ_API_KEY to enable Groq AI.' });
  }
  return risks;
}

/** Number that counts up with GSAP when it first appears or changes. */
function Counter({ value }) {
  const ref = useRef(null);
  const shown = useRef(0);
  useEffect(() => {
    if (value == null || !ref.current) return undefined;
    const state = { n: shown.current };
    const tween = gsap.to(state, {
      n: value, duration: 1.2, ease: 'power3.out',
      onUpdate: () => { shown.current = state.n; if (ref.current) ref.current.textContent = Math.round(state.n).toLocaleString('en-IN'); },
    });
    return () => tween.kill();
  }, [value]);
  return <span ref={ref}>{value == null ? '—' : 0}</span>;
}

function Kpi({ icon, label, value, sub, tone, to }) {
  const body = (
    <>
      <div className="kpi-top">
        <span className="kpi-icon"><Icon name={icon} size={20} /></span>
        {to && <Icon name="arrow" size={16} className="kpi-go" />}
      </div>
      <div className="kpi-value"><Counter value={value} /></div>
      <div className="kpi-label">{label}</div>
      {sub && <div className="kpi-sub">{sub}</div>}
    </>
  );
  const className = `kpi${tone ? ` kpi-${tone}` : ''}`;
  return (
    <motion.div variants={rise} whileHover={{ y: -4, rotateX: 4, rotateY: -4 }} transition={{ type: 'spring', stiffness: 300, damping: 20 }}
                className="kpi-wrap">
      {to ? <Link to={to} className={className}>{body}</Link> : <div className={className}>{body}</div>}
    </motion.div>
  );
}

const DONUT_COLORS = {
  READY_FOR_COMPARISON: '#ff0000', NEEDS_REVIEW: 'rgba(255, 0, 0, .5)', PROCESSING: 'rgba(255, 0, 0, .22)',
  UPLOADED: 'rgba(0, 0, 0, .25)', FAILED: '#000000',
};

function Donut({ counts }) {
  const entries = Object.entries(counts).filter(([, n]) => n > 0);
  const total = entries.reduce((a, [, n]) => a + n, 0);
  if (!total) return <EmptyState title="No quotations yet">Vendors' quotations appear here once they are sent.</EmptyState>;
  const r = 52;
  const circumference = 2 * Math.PI * r;
  let offset = 0;
  return (
    <div className="donut">
      <svg viewBox="0 0 140 140" className="donut-svg" role="img" aria-label={`Quotations by status, ${total} in total`}>
        <circle cx="70" cy="70" r={r} className="donut-track" />
        {entries.map(([status, n], i) => {
          const fraction = n / total;
          const seg = (
            <motion.circle key={status} cx="70" cy="70" r={r} fill="none" stroke={DONUT_COLORS[status] || 'rgba(0, 0, 0, .4)'} strokeWidth="16"
                           transform={`rotate(${offset * 360 - 90} 70 70)`}
                           initial={{ strokeDasharray: `0 ${circumference}`, opacity: 0 }}
                           animate={{ strokeDasharray: `${fraction * circumference} ${circumference}`, opacity: 1 }}
                           transition={{ duration: 0.9, delay: 0.2 + i * 0.12, ease: 'easeOut' }} />
          );
          offset += fraction;
          return seg;
        })}
        <text x="70" y="66" textAnchor="middle" className="donut-total">{total}</text>
        <text x="70" y="84" textAnchor="middle" className="donut-caption">quotations</text>
      </svg>
      <ul className="legend">
        {entries.map(([status, n]) => (
          <li key={status}>
            <span className="legend-swatch" style={{ background: DONUT_COLORS[status] || 'rgba(0, 0, 0, .4)' }} />
            <span>{humanize(status)}</span>
            <strong>{n}</strong>
            <span className="muted small">{Math.round((n / total) * 100)}%</span>
          </li>
        ))}
      </ul>
    </div>
  );
}

function StatusBars({ counts }) {
  const total = sum(counts);
  if (!total) return <p className="muted">Nothing yet.</p>;
  return (
    <ul className="bars">
      {Object.entries(counts).map(([status, count], i) => (
        <li key={status}>
          <span className="bar-label">{status === 'APPROVED' ? 'Awarded' : status === 'UNDER_REVIEW' ? 'Comparing' : humanize(status)}</span>
          <span className="bar-track">
            <motion.span className="bar-fill" initial={{ width: 0 }} animate={{ width: `${(count / total) * 100}%` }}
                         transition={{ duration: 0.8, delay: 0.3 + i * 0.06, ease: 'easeOut' }} />
          </span>
          <span className="bar-value">{count}</span>
        </li>
      ))}
    </ul>
  );
}

function QuickActions({ user }) {
  const actions = [
    can.createRequest(user) && { to: '/purchase-requests', icon: 'plus', label: 'Create RFQ', text: 'List parts and open to vendors' },
    can.workOnRfqs(user) && { to: '/purchase-requests', icon: 'upload', label: 'Review quotations', text: 'Check extracted offers' },
    can.viewVendors(user) && { to: '/vendors', icon: 'vendors', label: 'Vendors', text: can.manageMasterData(user) ? 'Add or edit suppliers' : 'Look up suppliers' },
    can.viewAwardRegister(user) && { to: '/awards', icon: 'award', label: 'Award register', text: 'Contracts awarded' },
    can.viewAudit(user) && { to: '/audit', icon: 'compliance', label: 'Compliance trail', text: 'Who did what, when' },
    can.manageUsers(user) && { to: '/users', icon: 'users', label: 'Users & roles', text: 'Logins for staff and vendors' },
  ].filter(Boolean);
  return (
    <div className="quick">
      {actions.map((a) => (
        <motion.div key={a.label} whileHover={{ scale: 1.03 }} whileTap={{ scale: 0.97 }}>
          <Link to={a.to} className="quick-item">
            <span className="quick-icon"><Icon name={a.icon} size={18} /></span>
            <span><strong>{a.label}</strong><span className="muted small">{a.text}</span></span>
          </Link>
        </motion.div>
      ))}
    </div>
  );
}

function Activity({ user, activity, rfqs }) {
  if (can.viewAudit(user)) {
    if (activity.loading && !activity.data) return <Spinner />;
    if (activity.error) return <ErrorBanner message={activity.error} onRetry={activity.reload} />;
    const entries = activity.data?.content || [];
    if (!entries.length) return <EmptyState title="No activity yet" />;
    return (
      <ol className="timeline">
        {entries.map((e) => (
          <li key={e.id}>
            <span className="timeline-dot" />
            <div>
              <strong>{humanize(e.action)}</strong> <span className="muted">· {humanize(e.entityType)} #{e.entityId}</span>
              <div className="muted small">{e.performedBy} · {formatDateTime(e.performedAt)}</div>
            </div>
          </li>
        ))}
      </ol>
    );
  }
  if (!rfqs.length) return <EmptyState title="No RFQs yet">{can.createRequest(user) ? 'Create one from RFQs in the menu.' : 'A buyer has not created any RFQ yet.'}</EmptyState>;
  return (
    <ol className="timeline">
      {rfqs.slice(0, 8).map((r) => (
        <li key={r.id}>
          <span className="timeline-dot" />
          <div>
            <Link to={`/purchase-requests/${r.id}`}><strong>{r.title}</strong></Link> <StatusBadge status={r.status} />
            <div className="muted small">{r.requestNumber} · created by {r.requestedBy} · {formatDate(r.createdAt)}</div>
          </div>
        </li>
      ))}
    </ol>
  );
}

function Health({ engines, services, d }) {
  return (
    <ul className="service-list">
      {(engines.data?.engines || []).map((e) => (
        <li key={e.engine}>
          <span className={`dot ${e.available ? 'dot-on' : 'dot-off'}`} />
          <strong>{e.label || (e.engine === 'GROQ' ? 'Groq AI' : 'Rule-based reader')}</strong>
          <span className="muted small">{e.available ? (e.models?.length ? e.models.join(', ') : 'available') : 'not available'}</span>
          {d?.extractionEngine === e.engine && e.available && <span className="badge badge-danger">used first</span>}
        </li>
      ))}
      {services.data?.map((s) => (
        <li key={s.service}>
          <span className={`dot ${s.up ? 'dot-on' : 'dot-off'}`} />
          {humanize(s.service)}
          <span className="service-ms">{s.up ? `${s.responseMs} ms` : 'down'}</span>
        </li>
      ))}
      {services.error && <ErrorBanner message={services.error} onRetry={services.reload} />}
    </ul>
  );
}

/** For a new installation: the steps of the workflow, ticked off from real counts. */
function GettingStarted({ d }) {
  const steps = [
    { done: (d.entities || 0) > 0, text: <>Add your company under <Link to="/entities">Entities</Link></> },
    { done: (d.vendors || 0) > 0, text: <>Add your suppliers under <Link to="/vendors">Vendors</Link></> },
    { done: false, text: <>Create logins under <Link to="/users">Users &amp; roles</Link>: buyers and managers (vendors register themselves and you approve them there)</> },
    { done: sum(d.purchaseRequestsByStatus) > 0, text: 'A buyer creates an RFQ and opens it to vendors' },
    { done: sum(d.quotationsByStatus) > 0, text: 'Vendors sign in and send their quotations' },
    { done: (d.purchaseRequestsByStatus?.APPROVED || 0) > 0, text: 'The buyer compares them and a manager awards the RFQ' },
  ];
  if (steps.every((s, i) => s.done || i === 2)) return null;
  return (
    <motion.section variants={rise} className="card">
      <h2>Getting started</h2>
      <ol className="welcome-steps">
        {steps.map((s, i) => <li key={i} className={s.done ? 'muted' : ''}>{s.done ? <s>{s.text}</s> : s.text}</li>)}
      </ol>
    </motion.section>
  );
}
