import { useState } from 'react';
import Icon from '../components/Icon.jsx';
import { usePlatform } from '../lib/platform.jsx';

/** Config form for any widget type, generated from the backend widget manifest. */
export function ConfigEditor({ type, config, onChange }) {
  const { manifest } = usePlatform();
  if (type === 'spec') {
    return <div className="muted" style={{ fontSize: 12 }}>Library widget <span className="mono">{config.definitionId}</span>. Its data and display
      come from the definition; change them in the “Write a spec” tab of <b>+ Widget</b>.</div>;
  }
  if (!manifest) return <div className="muted" style={{ fontSize: 12 }}>Loading widget options…</div>;
  const t = manifest.types[type];
  if (!t) return <div className="muted" style={{ fontSize: 12 }}>Unknown widget type.</div>;
  const fields = Object.entries(t.config);
  if (!fields.length) return <div className="muted" style={{ fontSize: 12 }}>No configurable filters.</div>;
  return (
    <div className="grid" style={{ gap: 10 }}>
      {fields.map(([f, allowed]) => (
        <label key={f} className="field" style={{ gridColumn: 'span 4' }}>
          <span className="label">{f}</span>
          {Array.isArray(allowed) ? (
            <select value={config[f] || ''} onChange={(e) => onChange({ ...config, [f]: e.target.value || undefined })}>
              <option value="">All</option>
              {allowed.map((o) => <option key={o} value={o}>{o}</option>)}
            </select>
          ) : (
            <input value={config[f] || ''} placeholder={f === 'marketId' ? 'e.g. SFO' : f === 'clientId' ? 'e.g. C012' : f === 'limit' ? '10' : ''}
              onChange={(e) => onChange({ ...config, [f]: f === 'limit' ? (parseInt(e.target.value, 10) || undefined) : (e.target.value.toUpperCase() || undefined) })} />
          )}
        </label>
      ))}
    </div>
  );
}

/** Card chrome around every dynamic widget: title, provenance badge, move / resize / configure / remove. */
export default function WidgetFrame({ pageId, widget, children, bare }) {
  const { removeWidget, moveWidget, updateWidget } = usePlatform();
  const [editing, setEditing] = useState(false);
  const [draft, setDraft] = useState(widget.config || {});
  const [title, setTitle] = useState(widget.title);
  const [span, setSpan] = useState(widget.span || 12);

  const by = widget.createdBy && widget.createdBy !== 'system' ? widget.createdBy : null;
  const tools = (
    <div className="card-tools">
      <button className="icon-btn" title="Move up" onClick={() => moveWidget(pageId, widget.id, -1)}><Icon name="up" size={14} /></button>
      <button className="icon-btn" title="Move down" onClick={() => moveWidget(pageId, widget.id, 1)}><Icon name="down" size={14} /></button>
      <button className="icon-btn" title="Configure" onClick={() => setEditing((e) => !e)}><Icon name="gear" size={14} /></button>
      <button className="icon-btn" title="Remove" onClick={() => removeWidget(pageId, widget.id)}><Icon name="x" size={14} /></button>
    </div>
  );

  const save = async () => {
    const clean = Object.fromEntries(Object.entries(draft).filter(([, v]) => v !== undefined && v !== ''));
    await updateWidget(pageId, widget.id, { title, span, config: clean });
    setEditing(false);
  };

  const editor = editing && (
    <div className="card-body" style={{ borderBottom: '1px solid var(--line)', background: '#fbfbf7' }}>
      <div className="grid" style={{ gap: 10, marginBottom: 10 }}>
        <label className="field" style={{ gridColumn: 'span 8' }}><span className="label">Title</span>
          <input value={title} onChange={(e) => setTitle(e.target.value)} /></label>
        <label className="field" style={{ gridColumn: 'span 4' }}><span className="label">Width (1-12)</span>
          <select value={span} onChange={(e) => setSpan(parseInt(e.target.value, 10))}>
            {[3, 4, 5, 6, 7, 8, 9, 12].map((n) => <option key={n} value={n}>{n}</option>)}
          </select></label>
      </div>
      <ConfigEditor type={widget.type} config={draft} onChange={setDraft} />
      <div className="row" style={{ marginTop: 12, justifyContent: 'flex-end' }}>
        <button className="btn ghost small" onClick={() => setEditing(false)}>Cancel</button>
        <button className="btn primary small" onClick={save}>Apply</button>
      </div>
    </div>
  );

  return (
    <div className={bare ? '' : 'card'} style={{ gridColumn: `span ${widget.span || 12}`, position: 'relative' }}>
      {bare ? (
        <div className="card" style={{ border: 0, background: 'transparent' }}>
          <div className="row" style={{ position: 'absolute', right: 6, top: -4, zIndex: 3 }}>{tools}</div>
          {editor && <div className="card" style={{ marginBottom: 10 }}>{editor}</div>}
          {children}
        </div>
      ) : (
        <>
          <div className="card-head">
            <h3>{widget.title}</h3>
            {by && <span className="by" title="Created by skill / agent">⚡ {by}</span>}
            {widget.visibleTo && <span className="tag violet" title="Not approved yet: only you see it on this page until a Reviewer approves it">Only you · not approved yet</span>}
            {tools}
          </div>
          {editor}
          {children}
        </>
      )}
    </div>
  );
}
