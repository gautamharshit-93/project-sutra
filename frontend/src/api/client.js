import axios from 'axios';

const TOKEN_KEY = 'sutra.token';

export const tokenStore = {
  get() {
    try {
      return sessionStorage.getItem(TOKEN_KEY);
    } catch {
      return null;
    }
  },
  set(token) {
    try {
      if (token) sessionStorage.setItem(TOKEN_KEY, token);
      else sessionStorage.removeItem(TOKEN_KEY);
    } catch {
      /* storage unavailable: the session simply won't survive a reload */
    }
  },
};

export const http = axios.create({ baseURL: '/api', timeout: 60000 });

http.interceptors.request.use((config) => {
  const token = tokenStore.get();
  if (token) config.headers.Authorization = `Bearer ${token}`;
  return config;
});

let onUnauthorized = () => {};
/** The auth context registers a callback so an expired token logs the user out everywhere. */
export function setUnauthorizedHandler(handler) {
  onUnauthorized = handler;
}

http.interceptors.response.use(
  (response) => response,
  (error) => {
    if (error.response?.status === 401 && !error.config?.url?.endsWith('/auth/login')) {
      onUnauthorized();
    }
    return Promise.reject(error);
  },
);

/** Turns the backend's ApiError (or a network failure) into one readable message. */
export function errorMessage(error) {
  const data = error?.response?.data;
  if (data?.fieldErrors?.length) {
    return data.fieldErrors.map((f) => `${f.field}: ${f.message}`).join('; ');
  }
  if (data?.message) {
    // Server-side failures carry a reference that support can find in the backend log
    return error.response.status >= 500 && data.correlationId ? `${data.message} (reference ${data.correlationId})` : data.message;
  }
  if (error?.response?.status === 413) return 'The file is too large.';
  if (error?.code === 'ERR_NETWORK') return 'Cannot reach the server. Is the backend running?';
  return error?.message || 'Something went wrong';
}

/** Field-level errors keyed by field name, for forms. */
export function fieldErrors(error) {
  const result = {};
  for (const f of error?.response?.data?.fieldErrors || []) result[f.field] = f.message;
  return result;
}
