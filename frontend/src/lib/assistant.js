import { api, qs, stream } from './api.js';

/** The widget assistant API (backend com.aipb.assistant). Messages and verify requests stream progress events. */
export const assistant = {
  status: () => api.get('/api/assistant/status'),
  // opts: { templateId, inputs } (a Skills Studio template and its form values) or { modifies } (a library widget id to change)
  create: (layoutId, pageId, params, opts = {}) => api.post('/api/assistant/sessions', { layoutId, pageId, params, ...opts }),
  session: (id) => api.get('/api/assistant/sessions/' + id),
  runs: (id) => api.get('/api/assistant/sessions/' + id + '/runs'),
  message: (id, text, params, onEvent) => stream('/api/assistant/sessions/' + id + '/messages', { text, params }, onEvent),
  verify: (id, onEvent) => stream('/api/assistant/sessions/' + id + '/verify', {}, onEvent),
  approve: (id, confirmUnverified, note) => api.post('/api/assistant/sessions/' + id + '/approve', { confirmUnverified, note }),
  submit: (id, note) => api.post('/api/assistant/sessions/' + id + '/submit', { note }),
};
