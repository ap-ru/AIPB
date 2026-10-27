import { api } from './api.js';
import { LAYOUT_ID } from './platform.jsx';

/** The Skills Studio catalog (backend com.aipb.skills): templates an assistant run is started from. */
export const skills = {
  templates: (pageId) => api.get('/api/skills/templates' + (pageId ? `?layoutId=${LAYOUT_ID}&pageId=${encodeURIComponent(pageId)}` : '')),
  template: (id) => api.get('/api/skills/templates/' + encodeURIComponent(id)),
};

export const CUSTOM_WIDGET = 'custom-widget';

export const KIND_LABELS = { widget: 'Widget', insight: 'Insight', action: 'Action' };

/** A page's scope for templates: client when it supplies clientId, book otherwise (as on the backend). */
export const pageScope = (page) => ((page?.params || []).some((p) => p.name === 'clientId') ? 'client' : 'book');

export const fits = (template, page) => template.pageScopes.includes(pageScope(page));
