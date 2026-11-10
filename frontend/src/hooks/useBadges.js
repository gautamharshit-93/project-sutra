import { useCallback, useEffect, useState } from 'react';
import { reportingApi, usersApi, vendorPortalApi } from '../api/endpoints.js';
import { can } from '../utils/permissions.js';

const SEEN_KEY = 'sutra.seenOutcomes';
const POLL_MS = 60000;

function seenOutcomes() {
  try {
    return new Set(JSON.parse(localStorage.getItem(SEEN_KEY) || '[]'));
  } catch {
    return new Set();
  }
}

/** Remembers (in this browser) that the vendor has seen these quotations' outcomes. */
function markOutcomesSeen(ids) {
  try {
    const seen = seenOutcomes();
    ids.forEach((id) => seen.add(id));
    localStorage.setItem(SEEN_KEY, JSON.stringify([...seen]));
  } catch { /* storage unavailable: the badge simply comes back */ }
}

/**
 * Counts for the menu badges, refreshed every minute and whenever the page changes:
 * { '/purchase-requests': n, '/users': n, '/portal': n, '/portal/quotations': n } keyed by the menu path.
 */
export function useBadges(user, pathname) {
  const [badges, setBadges] = useState({});

  const load = useCallback(async () => {
    const next = {};
    try {
      if (can.useVendorPortal(user)) {
        const b = await vendorPortalApi.badges();
        if (pathname === '/portal/quotations') markOutcomesSeen(b.decidedQuotationIds);
        const seen = seenOutcomes();
        next['/portal'] = b.openToQuote;
        next['/portal/quotations'] = b.decidedQuotationIds.filter((id) => !seen.has(id)).length;
      } else if (can.isStaff(user)) {
        const b = await reportingApi.badges();
        next['/purchase-requests'] = b.rfqsToCompare + b.awaitingAward;
        if (can.manageUsers(user)) next['/users'] = (await usersApi.vendorSignups()).length;
      }
      setBadges(next);
    } catch {
      /* badges are a convenience: keep the last counts */
    }
  }, [user, pathname]);

  useEffect(() => {
    load();
    const timer = setInterval(load, POLL_MS);
    return () => clearInterval(timer);
  }, [load]);

  return badges;
}
