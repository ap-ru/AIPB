import { createContext, useCallback, useContext, useEffect, useMemo, useState } from 'react';
import { api, setApiRole } from './api.js';

const Ctx = createContext(null);
export const LAYOUT_ID = 'default';

const ROLE_KEY = 'aipb.role';
function savedRole() {
  try { return localStorage.getItem(ROLE_KEY) || 'banker'; } catch (e) { return 'banker'; }
}

/**
 * Platform context: holds the dynamic layout (pages + widgets) fetched from MongoDB via the API,
 * a global refresh tick, and the layout-editing helpers used by pages and widgets.
 */
export function PlatformProvider({ children }) {
  // Who the app acts as. No sign-in yet: the role is picked from a dropdown, sent as X-AIPB-Role on every request.
  const [role, setRoleState] = useState(() => { const r = savedRole(); setApiRole(r); return r; });
  const [roles, setRoles] = useState(null);
  const [layout, setLayout] = useState(null);
  const [error, setError] = useState(null);
  const [tick, setTick] = useState(0);
  const [status, setStatus] = useState(null);
  // The source-data connections (the bank graph read directly for pages; the Cypher MCP server for the assistant): GET /api/source/status.
  const [source, setSource] = useState(null);
  // Widget types, their config schemas and the query datasets (GET /api/widgets/manifest).
  const [manifest, setManifest] = useState(null);
  // True while a page shows the full-page assistant; the shell collapses the sidebar to a rail.
  const [assistantOpen, setAssistantOpen] = useState(false);

  const loadLayout = useCallback(() => {
    api.get('/api/layouts/' + LAYOUT_ID).then(setLayout).catch(setError);
  }, []);

  useEffect(() => {
    loadLayout();
    api.get('/api/status').then(setStatus).catch(() => setStatus({ offline: true }));
    api.get('/api/source/status').then(setSource).catch(() => setSource({ connected: false }));
    api.get('/api/widgets/manifest').then(setManifest).catch(() => {});
    api.get('/api/identity').then((i) => setRoles(i.roles)).catch(() => {});
  }, [loadLayout]);

  const refresh = useCallback(() => setTick((t) => t + 1), []);

  const setRole = useCallback((r) => {
    setApiRole(r);
    try { localStorage.setItem(ROLE_KEY, r); } catch (e) { /* the choice just won't persist */ }
    setRoleState(r);
    setTick((t) => t + 1); // libraries differ per role
  }, []);
  // What the current role may do (Banker until the role list loads).
  const can = useMemo(() => (roles || []).find((r) => r.id === role)
    || { id: role, canUseAssistant: false, canAuthor: false, canReview: false }, [roles, role]);

  const layoutCall = useCallback(async (promise) => {
    const l = await promise;
    if (l && l.pages) setLayout(l);
    return l;
  }, []);

  const value = useMemo(() => ({
    layout, setLayout, error, tick, refresh, status, source, manifest, loadLayout, assistantOpen, setAssistantOpen,
    role, setRole, roles, can,
    addWidget: (pageId, widget) => layoutCall(api.post(`/api/layouts/${LAYOUT_ID}/pages/${pageId}/widgets`, widget)),
    removeWidget: (pageId, widgetId) => layoutCall(api.del(`/api/layouts/${LAYOUT_ID}/pages/${pageId}/widgets/${widgetId}`)),
    updateWidget: (pageId, widgetId, patch) => layoutCall(api.patch(`/api/layouts/${LAYOUT_ID}/pages/${pageId}/widgets/${widgetId}`, patch)),
    moveWidget: (pageId, widgetId, delta) => layoutCall(api.post(`/api/layouts/${LAYOUT_ID}/pages/${pageId}/widgets/${widgetId}/move?delta=${delta}`)),
    addPage: (page) => layoutCall(api.post(`/api/layouts/${LAYOUT_ID}/pages`, page)),
    deletePage: (pageId, force = false) => layoutCall(api.del(`/api/layouts/${LAYOUT_ID}/pages/${pageId}${force ? '?force=true' : ''}`)),
    resetLayout: () => layoutCall(api.post(`/api/layouts/${LAYOUT_ID}/reset`)),
  }), [layout, error, tick, refresh, status, source, manifest, loadLayout, assistantOpen, layoutCall, role, setRole, roles, can]);

  return <Ctx.Provider value={value}>{children}</Ctx.Provider>;
}

export function usePlatform() {
  const v = useContext(Ctx);
  if (!v) throw new Error('usePlatform outside PlatformProvider');
  return v;
}
