import { useEffect, useRef } from 'react';
import { humanize } from '../utils/format.js';

export function Spinner({ label = 'Loading' }) {
  return (
    <div className="spinner-wrap" role="status">
      <span className="spinner" aria-hidden="true" />
      <span>{label}…</span>
    </div>
  );
}

export function ErrorBanner({ message, onRetry }) {
  if (!message) return null;
  return (
    <div className="notice notice-error" role="alert">
      <span>{message}</span>
      {onRetry && (
        <button type="button" className="btn btn-small" onClick={onRetry}>Retry</button>
      )}
    </div>
  );
}

export function EmptyState({ title, children }) {
  return (
    <div className="empty">
      <p className="empty-title">{title}</p>
      {children && <div className="empty-body">{children}</div>}
    </div>
  );
}

const STATUS_TONES = {
  DRAFT: 'neutral', OPEN: 'info', UNDER_REVIEW: 'warning', APPROVED: 'success', REJECTED: 'danger',
  CANCELLED: 'neutral', UPLOADED: 'neutral', PROCESSING: 'info', NEEDS_REVIEW: 'warning',
  READY_FOR_COMPARISON: 'success', FAILED: 'danger', ERROR: 'danger', WARNING: 'warning', INFO: 'info',
  GEMINI: 'info', RULES: 'neutral', MANUAL: 'neutral',
};

/** Names that humanize() would get wrong (extraction engines). */
const STATUS_LABELS = { GEMINI: 'Gemini', RULES: 'Rule-based', MANUAL: 'Entered by hand', APPROVED: 'Awarded', UNDER_REVIEW: 'Comparing' };

export function StatusBadge({ status }) {
  if (!status) return null;
  return <span className={`badge badge-${STATUS_TONES[status] || 'neutral'}`}>{STATUS_LABELS[status] || humanize(status)}</span>;
}

/** Labelled form control with inline error text. Pass the input as children. */
export function Field({ label, error, hint, required, children, wide }) {
  return (
    <label className={`field${wide ? ' field-wide' : ''}`}>
      <span className="field-label">
        {label}
        {required && <span className="required" aria-hidden="true"> *</span>}
      </span>
      {children}
      {hint && !error && <span className="field-hint">{hint}</span>}
      {error && <span className="field-error">{error}</span>}
    </label>
  );
}

export function Modal({ title, onClose, children, footer, size }) {
  const dialogRef = useRef(null);
  useEffect(() => {
    const onKey = (e) => e.key === 'Escape' && onClose();
    document.addEventListener('keydown', onKey);
    dialogRef.current?.querySelector('input, select, textarea, button')?.focus();
    return () => document.removeEventListener('keydown', onKey);
  }, [onClose]);

  return (
    <div className="modal-backdrop" onMouseDown={(e) => e.target === e.currentTarget && onClose()}>
      <div className={`modal${size === 'large' ? ' modal-large' : ''}`} role="dialog" aria-modal="true"
           aria-label={title} ref={dialogRef}>
        <header className="modal-header">
          <h2>{title}</h2>
          <button type="button" className="icon-btn" aria-label="Close" onClick={onClose}>×</button>
        </header>
        <div className="modal-body">{children}</div>
        {footer && <footer className="modal-footer">{footer}</footer>}
      </div>
    </div>
  );
}

export function ConfirmDialog({ title, message, confirmLabel = 'Confirm', danger, busy, onConfirm, onCancel }) {
  return (
    <Modal title={title} onClose={onCancel} footer={(
      <>
        <button type="button" className="btn" onClick={onCancel}>Cancel</button>
        <button type="button" className={`btn ${danger ? 'btn-danger' : 'btn-primary'}`} disabled={busy}
                onClick={onConfirm}>{busy ? 'Working…' : confirmLabel}</button>
      </>
    )}>
      {typeof message === 'string' ? <p>{message}</p> : <div className="confirm-body">{message}</div>}
    </Modal>
  );
}

export function Pagination({ page, totalPages, onChange }) {
  if (!totalPages || totalPages <= 1) return null;
  return (
    <nav className="pagination" aria-label="Pagination">
      <button type="button" className="btn btn-small" disabled={page === 0} onClick={() => onChange(page - 1)}>
        Previous
      </button>
      <span>Page {page + 1} of {totalPages}</span>
      <button type="button" className="btn btn-small" disabled={page + 1 >= totalPages}
              onClick={() => onChange(page + 1)}>
        Next
      </button>
    </nav>
  );
}

export function Tabs({ tabs, active, onChange }) {
  return (
    <div className="tabs" role="tablist">
      {tabs.map((t) => (
        <button key={t.id} type="button" role="tab" aria-selected={active === t.id}
                className={`tab${active === t.id ? ' tab-active' : ''}`} onClick={() => onChange(t.id)}>
          {t.label}
          {t.count !== undefined && <span className="tab-count">{t.count}</span>}
        </button>
      ))}
    </div>
  );
}
