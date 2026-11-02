import { useEffect, useMemo, useRef, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { AnimatePresence, motion } from 'framer-motion';
import { purchaseRequestsApi, vendorsApi } from '../api/endpoints.js';
import { can } from '../utils/permissions.js';
import Icon from './Icon.jsx';
import { StatusBadge } from './ui.jsx';

/**
 * Search across pages, RFQs and vendors from the top bar (Ctrl+K). Only sources the role may read are queried;
 * the services check access again.
 */
export default function GlobalSearch({ user, pages }) {
  const navigate = useNavigate();
  const inputRef = useRef(null);
  const [query, setQuery] = useState('');
  const [open, setOpen] = useState(false);
  const [remote, setRemote] = useState({ rfqs: [], vendors: [], loading: false });
  const [active, setActive] = useState(0);

  useEffect(() => {
    const onKey = (e) => {
      if ((e.ctrlKey || e.metaKey) && e.key.toLowerCase() === 'k') {
        e.preventDefault();
        inputRef.current?.focus();
        setOpen(true);
      }
    };
    document.addEventListener('keydown', onKey);
    return () => document.removeEventListener('keydown', onKey);
  }, []);

  useEffect(() => {
    const q = query.trim();
    if (q.length < 2) {
      setRemote({ rfqs: [], vendors: [], loading: false });
      return undefined;
    }
    let cancelled = false;
    setRemote((r) => ({ ...r, loading: true }));
    const timer = setTimeout(async () => {
      const [rfqs, vendors] = await Promise.all([
        can.workOnRfqs(user) ? purchaseRequestsApi.list({ q, size: 5 }).catch(() => null) : null,
        can.viewVendors(user) ? vendorsApi.list({ q, size: 5 }).catch(() => null) : null,
      ]);
      if (!cancelled) setRemote({ rfqs: rfqs?.content || [], vendors: vendors?.content || [], loading: false });
    }, 220);
    return () => { cancelled = true; clearTimeout(timer); };
  }, [query, user]);

  const results = useMemo(() => {
    const q = query.trim().toLowerCase();
    if (!q) return [];
    const pageHits = pages.filter((p) => p.label.toLowerCase().includes(q))
      .map((p) => ({ key: `page-${p.to}`, group: 'Pages', icon: p.icon, title: p.label, to: p.to }));
    const rfqHits = remote.rfqs.map((r) => ({
      key: `rfq-${r.id}`, group: 'RFQs', icon: 'contracts', title: r.title, meta: r.requestNumber,
      status: r.status, to: `/purchase-requests/${r.id}`,
    }));
    const vendorHits = remote.vendors.map((v) => ({
      key: `vendor-${v.id}`, group: 'Vendors', icon: 'vendors', title: v.name, meta: v.code, to: '/vendors',
    }));
    return [...pageHits, ...rfqHits, ...vendorHits];
  }, [query, pages, remote]);

  useEffect(() => setActive(0), [results.length]);

  function go(result) {
    navigate(result.to);
    setQuery('');
    setOpen(false);
    inputRef.current?.blur();
  }

  function onKeyDown(e) {
    if (e.key === 'ArrowDown') { e.preventDefault(); setActive((a) => Math.min(a + 1, results.length - 1)); }
    if (e.key === 'ArrowUp') { e.preventDefault(); setActive((a) => Math.max(a - 1, 0)); }
    if (e.key === 'Enter' && results[active]) { e.preventDefault(); go(results[active]); }
    if (e.key === 'Escape') { setOpen(false); inputRef.current?.blur(); }
  }

  const showPanel = open && query.trim().length > 0;
  let lastGroup = null;

  return (
    <div className="gsearch" onBlur={(e) => !e.currentTarget.contains(e.relatedTarget) && setOpen(false)}>
      <Icon name="search" className="gsearch-icon" />
      <input ref={inputRef} type="search" className="gsearch-input" value={query} placeholder="Search RFQs, vendors, pages…"
             aria-label="Global search" role="combobox" aria-expanded={showPanel} aria-controls="gsearch-results"
             onFocus={() => setOpen(true)} onChange={(e) => { setQuery(e.target.value); setOpen(true); }} onKeyDown={onKeyDown} />
      <kbd className="gsearch-kbd">Ctrl K</kbd>
      <AnimatePresence>
        {showPanel && (
          <motion.div id="gsearch-results" className="gsearch-panel" role="listbox"
                      initial={{ opacity: 0, y: -6, scale: 0.98 }} animate={{ opacity: 1, y: 0, scale: 1 }}
                      exit={{ opacity: 0, y: -6, scale: 0.98 }} transition={{ duration: 0.16 }}>
            {results.length === 0 && (
              <p className="gsearch-empty">{remote.loading ? 'Searching…' : `No matches for “${query.trim()}”`}</p>
            )}
            {results.map((r, i) => {
              const header = r.group !== lastGroup ? <div className="gsearch-group" key={`g-${r.group}`}>{r.group}</div> : null;
              lastGroup = r.group;
              return [
                header,
                <button key={r.key} type="button" role="option" aria-selected={i === active}
                        className={`gsearch-item${i === active ? ' gsearch-item-active' : ''}`}
                        onMouseEnter={() => setActive(i)} onMouseDown={(e) => e.preventDefault()} onClick={() => go(r)}>
                  <span className="gsearch-item-icon"><Icon name={r.icon} size={16} /></span>
                  <span className="gsearch-item-title">{r.title}</span>
                  {r.meta && <span className="gsearch-item-meta">{r.meta}</span>}
                  {r.status && <StatusBadge status={r.status} />}
                </button>,
              ];
            })}
          </motion.div>
        )}
      </AnimatePresence>
    </div>
  );
}
