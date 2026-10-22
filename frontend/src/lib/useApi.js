import { useEffect, useState, useCallback } from 'react';
import { api } from './api.js';
import { usePlatform } from './platform.jsx';

/** Fetches a GET endpoint; refetches when the path or the global refresh tick changes. */
export function useApi(path, deps = []) {
  const { tick } = usePlatform();
  const [state, setState] = useState({ data: null, error: null, loading: true });
  const [local, setLocal] = useState(0);

  useEffect(() => {
    if (!path) return undefined;
    let alive = true;
    setState((s) => ({ ...s, loading: true }));
    api.get(path)
      .then((data) => alive && setState({ data, error: null, loading: false }))
      .catch((error) => alive && setState({ data: null, error, loading: false }));
    return () => { alive = false; };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [path, tick, local, ...deps]);

  const reload = useCallback(() => setLocal((x) => x + 1), []);
  return { ...state, reload };
}
