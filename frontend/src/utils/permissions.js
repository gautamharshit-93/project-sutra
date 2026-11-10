/**
 * UI permission helpers. They only decide what to show; every service enforces each rule again
 * (@PreAuthorize, RecordAccessPolicy, ApprovalService and the vendor portal's own-vendor checks).
 */
/** Four roles: the administrator can do everything, the buyer does the company's work, vendors quote, the manager awards. */
export const ROLES = ['ADMIN', 'PROCUREMENT', 'APPROVER', 'VENDOR'];

/** What each role is called on screen. */
const ROLE_LABELS = {
  ADMIN: 'Administrator',
  PROCUREMENT: 'Buyer',
  APPROVER: 'Manager',
  VENDOR: 'Vendor',
};

export function roleLabel(role) {
  return ROLE_LABELS[role] || role;
}

export function hasRole(user, ...roles) {
  return Boolean(user?.roles?.some((r) => roles.includes(r)));
}

export const can = {
  /** A supplier's login: only the vendor portal. */
  useVendorPortal: (user) => hasRole(user, 'VENDOR'),
  /** The company's own users. */
  isStaff: (user) => hasRole(user, 'ADMIN', 'PROCUREMENT', 'APPROVER'),
  /** Entities, customers and vendors' details: buyers' work too. */
  manageMasterData: (user) => hasRole(user, 'ADMIN', 'PROCUREMENT'),
  /** Buyers look up vendors when uploading on their behalf; administrators manage them. */
  viewVendors: (user) => hasRole(user, 'ADMIN', 'PROCUREMENT'),
  manageUsers: (user) => hasRole(user, 'ADMIN'),
  viewAudit: (user) => hasRole(user, 'ADMIN', 'APPROVER'),
  /** The RFQ workspace: buyers run RFQs, managers award them. */
  workOnRfqs: (user) => hasRole(user, 'ADMIN', 'PROCUREMENT', 'APPROVER'),
  viewAwardRegister: (user) => hasRole(user, 'ADMIN', 'PROCUREMENT', 'APPROVER'),
  createRequest: (user) => hasRole(user, 'ADMIN', 'PROCUREMENT'),
  /** Admins may change any request; buyers only their own. */
  editRequest: (user, request) =>
    hasRole(user, 'ADMIN') || (hasRole(user, 'PROCUREMENT') && request?.requestedById === user?.id),
  /** Managers award, but never on a request they raised themselves (admins excepted). */
  approve: (user, request) =>
    hasRole(user, 'ADMIN') || (hasRole(user, 'APPROVER') && request?.requestedById !== user?.id),
};

/** Where a user lands after signing in. */
export function homePath(user) {
  return can.useVendorPortal(user) ? '/portal' : '/';
}
