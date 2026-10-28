import { api, qs } from './api.js';
import { LAYOUT_ID } from './platform.jsx';

/**
 * Skills: the Studio catalog of templates an assistant run starts from (com.aipb.skills), and maker-checker - the
 * Skills window, submissions and their decisions, and widget versions (com.aipb.widget).
 */
export const skills = {
  templates: (pageId) => api.get('/api/skills/templates' + (pageId ? `?layoutId=${LAYOUT_ID}&pageId=${encodeURIComponent(pageId)}` : '')),
  template: (id) => api.get('/api/skills/templates/' + encodeURIComponent(id)),
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

export const CUSTOM_WIDGET = 'custom-widget';

export const KIND_LABELS = { widget: 'Widget', insight: 'Insight', action: 'Action' };

/** A page's scope for templates: client when it supplies clientId, book otherwise (as on the backend). */
export const pageScope = (page) => ((page?.params || []).some((p) => p.name === 'clientId') ? 'client' : 'book');

export const fits = (template, page) => template.pageScopes.includes(pageScope(page));
