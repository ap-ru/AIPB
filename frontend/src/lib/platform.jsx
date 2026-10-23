import { createContext, useCallback, useContext, useEffect, useMemo, useState } from 'react';
import { api, setApiRole } from './api.js';

const Ctx = createContext(null);
export const LAYOUT_ID = 'default';

const ROLE_KEY = 'aipb.role';
function savedRole() {
  try { return localStorage.getItem(ROLE_KEY) === 'approver' ? 'approver' : 'builder'; } catch (e) { return 'builder'; }
}
setApiRole(savedRole());

/**
 * Platform context: holds the dynamic layout (pages + widgets) fetched from MongoDB via the API,
 * a global refresh tick, and the layout-editing helpers used by pages and widgets.
 */
export function PlatformProvider({ children }) {
  const [layout, setLayout] = useState(null);
  const [error, setError] = useState(null);
  const [tick, setTick] = useState(0);
  const [status, setStatus] = useState(null);
  // The source-data connection (MongoDB through the MCP server): GET /api/source/status.
  const [source, setSource] = useState(null);
  // Widget types, their config schemas and the query datasets (GET /api/widgets/manifest).
  const [manifest, setManifest] = useState(null);
  // True while a page shows the full-page assistant; the shell collapses the sidebar to a rail.
  const [assistantOpen, setAssistantOpen] = useState(false);
  // Maker-checker role: a builder's widgets wait for approval; an approver approves (and their own are approved as saved).
  const [role, setRoleState] = useState(savedRole);
  const setRole = useCallback((r) => {
    setApiRole(r);
    try { localStorage.setItem(ROLE_KEY, r); } catch (e) { /* private mode: the role lasts for this tab */ }
    setRoleState(r);
  }, []);

  const loadLayout = useCallback(() => {
    api.get('/api/layouts/' + LAYOUT_ID).then(setLayout).catch(setError);
  }, []);

  useEffect(() => {
    loadLayout();
    api.get('/api/status').then(setStatus).catch(() => setStatus({ offline: true }));
    api.get('/api/source/status').then(setSource).catch(() => setSource({ connected: false }));
    api.get('/api/widgets/manifest').then(setManifest).catch(() => {});
  }, [loadLayout]);

  const refresh = useCallback(() => setTick((t) => t + 1), []);

  const layoutCall = useCallback(async (promise) => {
    const l = await promise;
    if (l && l.pages) setLayout(l);
    return l;
  }, []);

  const value = useMemo(() => ({
    layout, setLayout, error, tick, refresh, status, source, manifest, loadLayout, assistantOpen, setAssistantOpen, role, setRole,
    addWidget: (pageId, widget) => layoutCall(api.post(`/api/layouts/${LAYOUT_ID}/pages/${pageId}/widgets`, widget)),
    removeWidget: (pageId, widgetId) => layoutCall(api.del(`/api/layouts/${LAYOUT_ID}/pages/${pageId}/widgets/${widgetId}`)),
    updateWidget: (pageId, widgetId, patch) => layoutCall(api.patch(`/api/layouts/${LAYOUT_ID}/pages/${pageId}/widgets/${widgetId}`, patch)),
    moveWidget: (pageId, widgetId, delta) => layoutCall(api.post(`/api/layouts/${LAYOUT_ID}/pages/${pageId}/widgets/${widgetId}/move?delta=${delta}`)),
    addPage: (page) => layoutCall(api.post(`/api/layouts/${LAYOUT_ID}/pages`, page)),
    deletePage: (pageId, force = false) => layoutCall(api.del(`/api/layouts/${LAYOUT_ID}/pages/${pageId}${force ? '?force=true' : ''}`)),
    resetLayout: () => layoutCall(api.post(`/api/layouts/${LAYOUT_ID}/reset`)),
  }), [layout, error, tick, refresh, status, source, manifest, loadLayout, assistantOpen, role, setRole, layoutCall]);

  return <Ctx.Provider value={value}>{children}</Ctx.Provider>;
}

export function usePlatform() {
  const v = useContext(Ctx);
  if (!v) throw new Error('usePlatform outside PlatformProvider');
  return v;
}
