import { http } from './client.js';

const data = (promise) => promise.then((r) => r.data);

export const authApi = {
  login: (username, password) => data(http.post('/auth/login', { username, password })),
  me: () => data(http.get('/auth/me')),
  changePassword: (currentPassword, newPassword) =>
    data(http.post('/auth/change-password', { currentPassword, newPassword })),
  /** Public: a supplier signs up; the login waits for an administrator's approval. */
  registerVendor: (body) => data(http.post('/auth/register-vendor', body)),
};

/** Generic CRUD for the master-data resources. */
function crud(path) {
  return {
    list: (params) => data(http.get(path, { params })),
    get: (id) => data(http.get(`${path}/${id}`)),
    create: (body) => data(http.post(path, body)),
    update: (id, body) => data(http.put(`${path}/${id}`, body)),
    remove: (id) => data(http.delete(`${path}/${id}`)),
  };
}

export const entitiesApi = {
  ...crud('/entities'),
  createSub: (entityId, body) => data(http.post(`/entities/${entityId}/sub-entities`, body)),
  updateSub: (entityId, subId, body) => data(http.put(`/entities/${entityId}/sub-entities/${subId}`, body)),
  removeSub: (entityId, subId) => data(http.delete(`/entities/${entityId}/sub-entities/${subId}`)),
};
export const customersApi = crud('/customers');
export const vendorsApi = crud('/vendors');
export const usersApi = {
  ...crud('/users'),
  roles: () => data(http.get('/users/roles')),
  vendorSignups: () => data(http.get('/users/vendor-signups')),
  approveVendor: (id) => data(http.post(`/users/${id}/approve-vendor`)),
  rejectVendor: (id) => data(http.delete(`/users/${id}/vendor-signup`)),
};

/** A vendor's own company details on the portal. */
export const vendorProfileApi = {
  get: () => data(http.get('/vendor-profile')),
  update: (body) => data(http.put('/vendor-profile', body)),
};

export const purchaseRequestsApi = {
  ...crud('/purchase-requests'),
  changeStatus: (id, status) => data(http.patch(`/purchase-requests/${id}/status`, { status })),
  /** Header + items (+ open to vendors) in one step. */
  createWithItems: (request, items, open) => data(http.post('/purchase-requests/with-items', { request, items, open })),
  /** Reads dropped RFQ files (PDF, scan, Word, Excel) into a draft; nothing is saved. */
  draftFromFiles: (files) => {
    const form = new FormData();
    files.forEach((f) => form.append('files', f));
    return data(http.post('/purchase-requests/draft-from-files', form, { timeout: 240000 }));
  },
  /** Reads pasted RFQ text into a draft; nothing is saved. */
  draftFromText: (text) => data(http.post('/purchase-requests/draft-from-text', { text }, { timeout: 240000 })),
  addItem: (id, body) => data(http.post(`/purchase-requests/${id}/items`, body)),
  updateItem: (id, itemId, body) => data(http.put(`/purchase-requests/${id}/items/${itemId}`, body)),
  removeItem: (id, itemId) => data(http.delete(`/purchase-requests/${id}/items/${itemId}`)),
  quotations: (id) => data(http.get(`/purchase-requests/${id}/quotations`)),
  /** One quotation, one or more files (e.g. the same offer as PDF, Word and Excel), read by `engine` (AUTO by default). */
  upload: (id, vendorId, files, onProgress, engine = 'AUTO', model = null) => {
    const form = new FormData();
    files.forEach((f) => form.append('files', f));
    return data(
      http.post(`/purchase-requests/${id}/quotations`, form, {
        params: { vendorId, engine, ...(model ? { model } : {}) },
        onUploadProgress: (e) => onProgress?.(e.total ? Math.round((e.loaded * 100) / e.total) : 0),
      }),
    );
  },
  comparisons: (id) => data(http.get(`/purchase-requests/${id}/comparisons`)),
  generateComparison: (id) => data(http.post(`/purchase-requests/${id}/comparisons`)),
  approvals: (id) => data(http.get(`/purchase-requests/${id}/approvals`)),
  decide: (id, body) => data(http.post(`/purchase-requests/${id}/approvals`, body)),
  /** Audit trail of the request, its quotations, comparisons and decisions. */
  history: (id) => data(http.get(`/purchase-requests/${id}/history`)),
  /** Downloads the award summary PDF (after a decision is recorded). */
  downloadAwardSummary: async (id, requestNumber) => {
    const response = await http.get(`/purchase-requests/${id}/award-summary`, { responseType: 'blob' });
    const url = URL.createObjectURL(response.data);
    const link = document.createElement('a');
    link.href = url;
    link.download = `${requestNumber || 'rfq'}-award-summary.pdf`;
    document.body.appendChild(link);
    link.click();
    link.remove();
    setTimeout(() => URL.revokeObjectURL(url), 1000);
  },
};

export const quotationsApi = {
  get: (id) => data(http.get(`/quotations/${id}`)),
  updateHeader: (id, body) => data(http.put(`/quotations/${id}`, body)),
  remove: (id) => data(http.delete(`/quotations/${id}`)),
  /** Reads the quotation's files again (Groq first, the rule-based reader as backup). */
  reprocess: (id) => data(http.post(`/quotations/${id}/reprocess`)),
  addItem: (id, body) => data(http.post(`/quotations/${id}/items`, body)),
  updateItem: (id, itemId, body) => data(http.put(`/quotations/${id}/items/${itemId}`, body)),
  removeItem: (id, itemId) => data(http.delete(`/quotations/${id}/items/${itemId}`)),
  resolveFlag: (id, flagId, comment) => data(http.post(`/quotations/${id}/flags/${flagId}/resolve`, { comment })),
  addFiles: (id, files) => {
    const form = new FormData();
    files.forEach((f) => form.append('files', f));
    return data(http.post(`/quotations/${id}/files`, form));
  },
  removeFile: (id, fileId) => data(http.delete(`/quotations/${id}/files/${fileId}`)),
  history: (id) => data(http.get(`/quotations/${id}/history`)),
  /** Downloads one original file through the authenticated client and hands it to the browser. */
  download: async (id, fileId, fileName) => {
    const response = await http.get(`/quotations/${id}/files/${fileId}`, { responseType: 'blob' });
    const url = URL.createObjectURL(response.data);
    const link = document.createElement('a');
    link.href = url;
    link.download = fileName || 'quotation';
    document.body.appendChild(link);
    link.click();
    link.remove();
    setTimeout(() => URL.revokeObjectURL(url), 1000);
  },
};

export const extractionApi = {
  /** { engines: [{ engine, label, model, models, available, inAutoOrder }], autoOrder: ['GROQ', 'RULES'] } */
  engines: () => data(http.get('/extraction-engines')),
};

/**
 * The vendor portal: a supplier's login sees open RFQs and only its own quotations (enforced by the procurement
 * service, not just hidden here).
 */
export const vendorPortalApi = {
  rfqs: (params) => data(http.get('/vendor-portal/rfqs', { params })),
  rfq: (id) => data(http.get(`/vendor-portal/rfqs/${id}`)),
  submit: (id, files, onProgress) => {
    const form = new FormData();
    files.forEach((f) => form.append('files', f));
    return data(http.post(`/vendor-portal/rfqs/${id}/quotations`, form, {
      onUploadProgress: (e) => onProgress?.(e.total ? Math.round((e.loaded * 100) / e.total) : 0),
    }));
  },
  quotations: () => data(http.get('/vendor-portal/quotations')),
  /** Menu badge counts: { openToQuote, decidedQuotationIds }. */
  badges: () => data(http.get('/vendor-portal/badges')),
  withdraw: (id) => data(http.delete(`/vendor-portal/quotations/${id}`)),
  download: async (id, fileId, fileName) => {
    const response = await http.get(`/vendor-portal/quotations/${id}/files/${fileId}`, { responseType: 'blob' });
    const url = URL.createObjectURL(response.data);
    const link = document.createElement('a');
    link.href = url;
    link.download = fileName || 'quotation';
    document.body.appendChild(link);
    link.click();
    link.remove();
    setTimeout(() => URL.revokeObjectURL(url), 1000);
  },
};

export const reportingApi = {
  dashboard: () => data(http.get('/dashboard')),
  /** Menu badge counts: { rfqsToCompare, awaitingAward } for the signed-in company user. */
  badges: () => data(http.get('/dashboard/badges')),
  /** Health of every service behind the gateway. */
  services: () => data(http.get('/system/services')),
  auditLogs: (params) => data(http.get('/audit-logs', { params })),
};

/**
 * SUTRA Sahayak, the read-only Hindi/English assistant. `chat` sends the question, the earlier turns
 * ([{ role: 'user'|'assistant', text }]) and the record on screen; it returns { reply, links: [{ type, id, label }] }.
 */
export const assistantApi = {
  status: () => data(http.get('/assistant/status')),
  chat: (body) => data(http.post('/assistant/chat', body, { timeout: 240000 })),
};
