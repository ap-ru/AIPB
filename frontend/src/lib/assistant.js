import { api, qs, stream } from './api.js';

/** The widget assistant API (backend com.aipb.assistant). Messages and verify requests stream progress events. */
export const assistant = {
  status: () => api.get('/api/assistant/status'),
  create: (layoutId, pageId, params, modifies) => api.post('/api/assistant/sessions', { layoutId, pageId, params, modifies }),
  session: (id) => api.get('/api/assistant/sessions/' + id),
  runs: (id) => api.get('/api/assistant/sessions/' + id + '/runs'),
  message: (id, text, params, onEvent) => stream('/api/assistant/sessions/' + id + '/messages', { text, params }, onEvent),
  verify: (id, onEvent) => stream('/api/assistant/sessions/' + id + '/verify', {}, onEvent),
  approve: (id, confirmUnverified, note) => api.post('/api/assistant/sessions/' + id + '/approve', { confirmUnverified, note }),
  submit: (id, note) => api.post('/api/assistant/sessions/' + id + '/submit', { note }),
};

/** Maker-checker: the Skills window, submissions and their decisions, and widget versions (com.aipb.widget). */
export const skills = {
  share: (widgetId, note) => api.post('/api/widgets/library/' + encodeURIComponent(widgetId) + '/submit', { note }),
  list: () => api.get('/api/skills'),
  submission: (id, params) => api.get('/api/submissions/' + id + qs(params)),
  approve: (id, comment) => api.post('/api/submissions/' + id + '/approve', { comment }),
  sendBack: (id, comment) => api.post('/api/submissions/' + id + '/send-back', { comment }),
  reject: (id, comment) => api.post('/api/submissions/' + id + '/reject', { comment }),
  preview: (widgetId, params) => api.get('/api/widgets/library/' + encodeURIComponent(widgetId) + '/preview' + qs(params)),
  versions: (widgetId) => api.get('/api/widgets/library/' + encodeURIComponent(widgetId) + '/versions'),
  version: (widgetId, v, params) => api.get('/api/widgets/library/' + encodeURIComponent(widgetId) + '/versions/' + v + qs(params)),
  restore: (widgetId, v) => api.post('/api/widgets/library/' + encodeURIComponent(widgetId) + '/versions/' + v + '/restore'),
};
