import { useApi } from '../../lib/useApi.js';
import { qs } from '../../lib/api.js';
import { LAYOUT_ID } from '../../lib/platform.jsx';
import SpecView from './SpecView.jsx';

/**
 * A placed library widget: config.definitionId names the definition. On a page, the widget is fetched by its
 * placement, with the page's context values (client, period, filters) from the URL: the server resolves them with the
 * placement's own bindings, so the browser never decides what a widget shows. Off a page (a preview), the definition
 * is run directly with the given values. The card states the context the figures were computed in, and any note.
 */
export default function SpecWidget({ config, widget, pageId, pageParams }) {
  const params = { ...(pageParams || {}), ...(config.params || {}) };
  const path = !config.definitionId ? null
    : widget?.id && pageId
      ? `/api/widgets/placed/${LAYOUT_ID}/${encodeURIComponent(pageId)}/${encodeURIComponent(widget.id)}/data${qs(pageParams || {})}`
      : `/api/widgets/library/${encodeURIComponent(config.definitionId)}/data${qs(params)}`;
  const q = useApi(path);
  let body;
  if (!config.definitionId) body = <div className="empty">This widget has no definition.</div>;
  else if (q.loading && !q.data) body = <Skeleton />;
  else if (q.error) body = <div className="error-box">⚠ {String(q.error.message || q.error)}</div>;
  else if (!q.data.rows.length) body = <div className="empty soft">No data for this selection.</div>;
  else body = <SpecView view={q.data.view} columns={q.data.columns} rows={q.data.rows} truncated={q.data.truncated} total={q.data.total} narrative={q.data.narrative} />;
  return (
    <div className={'card-body spec-body' + (q.loading && q.data ? ' refreshing' : '')}>
      {q.data?.description && (
        <div className="spec-sub">
          <span>{q.data.description}</span>
          {q.data.asOfDate && <span className="asof">as of {asOf(q.data.asOfDate)}</span>}
        </div>
      )}
      {q.data && <ContextLine data={q.data} onPage={Boolean(pageId)} />}
      {body}
    </div>
  );
}

/**
 * Whose figures, over which period and with which filters - as the server applied them - plus the semantic layer's
 * notes (e.g. a filter these figures cannot take). On a client page the client is already in the top bar, so only a
 * book-wide figure says so.
 */
function ContextLine({ data, onPage }) {
  const c = data.context;
  const parts = [];
  if (c?.scope === 'book' && onPage && !data.params?.clientId) parts.push('Whole book');
  // a current balance is already dated by the card's as-of stamp
  if (c?.period && !c.period.startsWith('all time') && !c.period.startsWith('current')) parts.push(cap(c.period));
  (c?.filters || []).forEach((f) => parts.push(f));
  const notes = data.notes || [];
  if (!parts.length && !notes.length) return null;
  return (
    <div className="spec-context">
      {parts.map((p) => <span key={p} className="spec-context-chip">{p}</span>)}
      {notes.map((n) => <span key={n} className="spec-context-note" title={n}>⚠ {n}</span>)}
    </div>
  );
}

const cap = (s) => s.charAt(0).toUpperCase() + s.slice(1);

const asOf = (d) => new Date(d).toLocaleDateString('en-US', { month: 'short', day: 'numeric', year: 'numeric', timeZone: 'UTC' });

/** Placeholder in the widget's own shape while its first result loads; later refetches keep the frame, dimmed. */
function Skeleton() {
  return (
    <div className="skeleton" aria-busy="true" aria-label="Loading">
      <span style={{ width: '38%', height: 22 }} />
      <span style={{ width: '64%' }} />
      <span style={{ width: '100%', height: 96, marginTop: 6 }} />
    </div>
  );
}
