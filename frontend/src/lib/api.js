const BASE = import.meta.env.VITE_API_BASE || '';

// The maker-checker role (the sidebar's Builder / Approver toggle), sent with every request.
let role = 'builder';
export function setApiRole(r) { role = r; }
const roleHeader = () => ({ 'X-AIPB-Role': role });

async function request(method, path, body) {
  const res = await fetch(BASE + path, {
    method,
    headers: body !== undefined ? { 'Content-Type': 'application/json', ...roleHeader() } : roleHeader(),
    body: body !== undefined ? JSON.stringify(body) : undefined,
  });
  if (!res.ok) {
    let msg = res.status + ' ' + res.statusText;
    let payload = null;
    try {
      payload = await res.json();
      if (payload.error) msg = payload.error;
    } catch (e) { /* ignore */ }
    const err = new Error(msg);
    err.status = res.status;
    err.body = payload; // e.g. the validation report of a refused widget spec (422)
    throw err;
  }
  if (res.status === 204) return null;
  const text = await res.text();
  return text ? JSON.parse(text) : null;
}

export const api = {
  get: (p) => request('GET', p),
  post: (p, b) => request('POST', p, b === undefined ? {} : b),
  put: (p, b) => request('PUT', p, b),
  patch: (p, b) => request('PATCH', p, b),
  del: (p) => request('DELETE', p),
};

/**
 * POSTs and reads a server-sent event stream, calling onEvent(name, data) for each event as it arrives.
 * Resolves when the server closes the stream.
 */
export async function stream(path, body, onEvent) {
  const res = await fetch(BASE + path, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', Accept: 'text/event-stream', ...roleHeader() },
    body: JSON.stringify(body),
  });
  if (!res.ok || !res.body) throw new Error(res.status + ' ' + res.statusText);
  const reader = res.body.getReader();
  const decoder = new TextDecoder();
  let buf = '';
  for (;;) {
    const { done, value } = await reader.read();
    if (done) break;
    buf += decoder.decode(value, { stream: true }).replace(/\r/g, '');
    let i;
    while ((i = buf.indexOf('\n\n')) >= 0) {
      const chunk = buf.slice(0, i);
      buf = buf.slice(i + 2);
      let event = 'message';
      let data = '';
      chunk.split('\n').forEach((line) => {
        if (line.startsWith('event:')) event = line.slice(6).trim();
        else if (line.startsWith('data:')) data += line.slice(5).replace(/^ /, '');
      });
      if (data) onEvent(event, JSON.parse(data));
    }
  }
}

/** Builds a query string from a widget config, dropping empty values. */
export function qs(params) {
  const q = Object.entries(params || {})
    .filter(([, v]) => v !== undefined && v !== null && v !== '' && v !== 'all')
    .map(([k, v]) => encodeURIComponent(k) + '=' + encodeURIComponent(v))
    .join('&');
  return q ? '?' + q : '';
}

/** Only the filter keys understood by data endpoints. */
export function filters(config, keys = ['lob', 'region', 'marketId']) {
  const out = {};
  keys.forEach((k) => {
    if (config && config[k]) out[k] = config[k];
  });
  return out;
}
