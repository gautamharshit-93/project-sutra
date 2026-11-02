import { useCallback, useEffect, useRef, useState } from 'react';
import { errorMessage } from '../api/client.js';

/**
 * Loads data with a promise-returning function and re-runs when `deps` change.
 * Returns { data, error, loading, reload, setData }. Stale responses are ignored.
 */
export function useApi(loader, deps = []) {
  const [state, setState] = useState({ data: null, error: null, loading: true });
  const requestId = useRef(0);

  const reload = useCallback(async ({ silent = false } = {}) => {
    const id = ++requestId.current;
    if (!silent) setState((s) => ({ ...s, loading: true, error: null }));
    try {
      const data = await loader();
      if (id === requestId.current) setState({ data, error: null, loading: false });
      return data;
    } catch (error) {
      if (id === requestId.current) setState((s) => ({ ...s, error: errorMessage(error), loading: false }));
      return undefined;
    }
  }, deps); // callers list the values `loader` depends on

  useEffect(() => {
    reload();
  }, [reload]);

  const setData = useCallback((updater) => {
    setState((s) => ({ ...s, data: typeof updater === 'function' ? updater(s.data) : updater }));
  }, []);

  return { ...state, reload, setData };
}
