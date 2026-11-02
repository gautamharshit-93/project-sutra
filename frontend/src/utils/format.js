/** Display helpers. Amounts arrive from the API as numbers or decimal strings; nothing here does arithmetic. */

export function formatMoney(value, currency = 'INR') {
  if (value === null || value === undefined || value === '') return '—';
  const number = Number(value);
  if (Number.isNaN(number)) return String(value);
  try {
    return new Intl.NumberFormat(currency === 'INR' ? 'en-IN' : 'en-US', {
      style: 'currency',
      currency: currency || 'INR',
      minimumFractionDigits: 2,
      maximumFractionDigits: 2,
    }).format(number);
  } catch {
    return `${currency} ${number.toFixed(2)}`;
  }
}

export function formatNumber(value, maxFractionDigits = 3) {
  if (value === null || value === undefined || value === '') return '—';
  const number = Number(value);
  if (Number.isNaN(number)) return String(value);
  return new Intl.NumberFormat('en-IN', { maximumFractionDigits: maxFractionDigits }).format(number);
}

export function formatPercent(value) {
  if (value === null || value === undefined) return '—';
  const number = Number(value);
  const sign = number > 0 ? '+' : '';
  return `${sign}${number.toFixed(2)}%`;
}

export function formatConfidence(value) {
  if (value === null || value === undefined) return '—';
  return `${Math.round(Number(value) * 100)}%`;
}

export function formatDate(value) {
  if (!value) return '—';
  const date = new Date(value.length === 10 ? `${value}T00:00:00` : value);
  if (Number.isNaN(date.getTime())) return value;
  return date.toLocaleDateString('en-IN', { day: '2-digit', month: 'short', year: 'numeric' });
}

export function formatDateTime(value) {
  if (!value) return '—';
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return value;
  return date.toLocaleString('en-IN', {
    day: '2-digit', month: 'short', year: 'numeric', hour: '2-digit', minute: '2-digit',
  });
}

export function formatBytes(bytes) {
  if (!bytes && bytes !== 0) return '—';
  if (bytes < 1024) return `${bytes} B`;
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`;
  return `${(bytes / (1024 * 1024)).toFixed(1)} MB`;
}

/** "READY_FOR_COMPARISON" -> "Ready for comparison" */
export function humanize(value) {
  if (!value) return '';
  const text = String(value).toLowerCase().replace(/_/g, ' ');
  return text.charAt(0).toUpperCase() + text.slice(1);
}

/** Empty strings become null so the API receives "not provided" rather than "". */
export function emptyToNull(object) {
  const result = {};
  for (const [key, value] of Object.entries(object)) {
    result[key] = typeof value === 'string' && value.trim() === '' ? null : value;
  }
  return result;
}
