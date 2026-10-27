import { api, stream } from './api.js';

/** The widget assistant API (backend com.aipb.assistant). Messages and verify requests stream progress events. */
export const assistant = {
  status: () => api.get('/api/assistant/status'),
  create: (layoutId, pageId, params, templateId, inputs) => api.post('/api/assistant/sessions', { layoutId, pageId, params, templateId, inputs }),
  session: (id) => api.get('/api/assistant/sessions/' + id),
  runs: (id) => api.get('/api/assistant/sessions/' + id + '/runs'),
  message: (id, text, params, onEvent) => stream('/api/assistant/sessions/' + id + '/messages', { text, params }, onEvent),
  verify: (id, onEvent) => stream('/api/assistant/sessions/' + id + '/verify', {}, onEvent),
  approve: (id, confirmUnverified) => api.post('/api/assistant/sessions/' + id + '/approve', { confirmUnverified }),
  submit: (id, note) => api.post('/api/assistant/sessions/' + id + '/submit', { note }),
};
