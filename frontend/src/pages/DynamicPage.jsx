import { useEffect, useState } from 'react';
import { useLocation, useNavigate, useParams, useSearchParams } from 'react-router-dom';
import { usePlatform, LAYOUT_ID } from '../lib/platform.jsx';
import { api } from '../lib/api.js';
import { useApi } from '../lib/useApi.js';
import { REGISTRY, DEFAULT_SPAN } from '../widgets/registry.jsx';
import WidgetFrame, { ConfigEditor } from '../widgets/WidgetFrame.jsx';
import SpecWorkbench from '../components/SpecWorkbench.jsx';
import AssistantChat from '../components/assistant/AssistantChat.jsx';
import Icon from '../components/Icon.jsx';
import { Async, Loading } from '../components/common.jsx';
import TopBar from '../components/TopBar.jsx';

/**
 * A page's context: its client (the entity picker, e.g. Client 360's), its period and its filters. Values live in the
 * URL (?clientId=...&period=ytd&region=EMEA), so a view can be shared; until a client is chosen the first option is
 * used, and the period starts at the page's default. Every widget on the page receives the values; the server
 * resolves them (with each placement's own bindings) into the context the widget runs in.
 */
function usePageParams(page) {
  const [search, setSearch] = useSearchParams();
  const [options, setOptions] = useState({});
  const [filterOptions, setFilterOptions] = useState({});
  const [error, setError] = useState(null);
  const defs = page?.params || [];
  const period = page?.context?.period || null;
  const filters = page?.context?.filters || [];
  const key = page ? page.id + ':' + defs.map((p) => p.name).join(',') + ':' + filters.map((f) => f.dimension).join(',') : '';
  useEffect(() => {
    setOptions({});
    setFilterOptions({});
    setError(null);
    defs.forEach((p) => {
      api.get(`/api/layouts/${LAYOUT_ID}/pages/${page.id}/params/${p.name}/options`)
        .then((o) => setOptions((s) => ({ ...s, [p.name]: o })))
        .catch(setError);
    });
    filters.forEach((f) => {
      api.get(`/api/layouts/${LAYOUT_ID}/pages/${page.id}/context/filters/${f.dimension}/options`)
        .then((o) => setFilterOptions((s) => ({ ...s, [f.dimension]: o })))
        .catch(setError);
    });
  }, [key]); // eslint-disable-line react-hooks/exhaustive-deps
  const values = {};
  defs.forEach((p) => { values[p.name] = search.get(p.name) || options[p.name]?.[0]?.value; });
  if (period) values.period = search.get('period') || period.defaultOption;
  filters.forEach((f) => { const v = search.get(f.dimension); if (v) values[f.dimension] = v; });
  const set = (name, value) => {
    const next = new URLSearchParams(search);
    if (value === undefined || value === null || value === '') next.delete(name); else next.set(name, value);
    setSearch(next);
  };
  return { defs, options, period, filters, filterOptions, values, ready: defs.every((p) => values[p.name]), error, set };
}

function LibraryTab({ pageId, onDone }) {
  const { addWidget } = usePlatform();
  const q = useApi('/api/widgets/library');
  const [problem, setProblem] = useState(null);
  const place = async (d) => {
    setProblem(null);
    try {
      await addWidget(pageId, { type: 'spec', title: d.title, span: 0, config: { definitionId: d.id } });
      onDone();
    } catch (e) { setProblem(e.message); }
  };
  return (
    <div className="stack" style={{ gap: 8 }}>
      {problem && <div className="error-box">⚠ {problem}</div>}
      <Async q={q} empty="The library is empty. Write a spec to add one.">
        {(defs) => defs.map((d) => (
          <div key={d.id} className="spec-list-item">
            <div style={{ minWidth: 0, flex: 1 }}>
              <div className="spec-list-title">{d.title} <span className="tag">{d.primitive}</span></div>
              <div className="muted" style={{ fontSize: 12 }}>{d.description}</div>
              <div className="mono dim" style={{ fontSize: 11, marginTop: 3 }}>
                {d.id} · v{d.version} · {d.collection}{d.params?.length ? ' · needs ' + d.params.join(', ') : ''}
              </div>
            </div>
            <button className="btn small" onClick={() => place(d)}>Place</button>
          </div>
        ))}
      </Async>
    </div>
  );
}

function BuiltInTab({ pageId, onDone }) {
  const { addWidget } = usePlatform();
  const types = Object.entries(REGISTRY).filter(([, v]) => !v.library);
  const [type, setType] = useState(types[0][0]);
  const [title, setTitle] = useState('');
  const [span, setSpan] = useState(DEFAULT_SPAN[types[0][0]] || 6);
  const [config, setConfig] = useState({});
  const save = async () => {
    const clean = Object.fromEntries(Object.entries(config).filter(([, v]) => v !== undefined && v !== ''));
    await addWidget(pageId, { type, title: title || REGISTRY[type].label, span, config: clean });
    onDone();
  };
  return (
    <div className="stack">
      <div className="muted" style={{ fontSize: 12 }}>Hand-built widgets over the app's demo data, kept for the reference page.</div>
      <div className="grid" style={{ gap: 10 }}>
        <label className="field" style={{ gridColumn: 'span 6' }}><span className="label">Widget type</span>
          <select value={type} onChange={(e) => { setType(e.target.value); setSpan(DEFAULT_SPAN[e.target.value] || 6); setConfig({}); }}>
            {types.map(([k, v]) => <option key={k} value={k}>{v.label}</option>)}
          </select></label>
        <label className="field" style={{ gridColumn: 'span 4' }}><span className="label">Title</span>
          <input value={title} placeholder={REGISTRY[type].label} onChange={(e) => setTitle(e.target.value)} /></label>
        <label className="field" style={{ gridColumn: 'span 2' }}><span className="label">Width</span>
          <select value={span} onChange={(e) => setSpan(parseInt(e.target.value, 10))}>
            {[3, 4, 6, 8, 12].map((n) => <option key={n} value={n}>{n}</option>)}
          </select></label>
      </div>
      <ConfigEditor type={type} config={config} onChange={setConfig} />
      <div className="row" style={{ justifyContent: 'flex-end' }}>
        <button className="btn primary" onClick={save}>Add to page</button>
      </div>
    </div>
  );
}

const TABS = [['library', 'Library'], ['spec', 'Write a spec'], ['builtin', 'Built-in']];

function AddWidgetModal({ page, pageParams, onClose }) {
  const { addWidget } = usePlatform();
  const [tab, setTab] = useState('library');
  const placeSaved = async (saved) => {
    await addWidget(page.id, { type: 'spec', title: saved.title, span: 0, config: { definitionId: saved.id } });
    onClose();
  };
  return (
    <div className="overlay" onClick={onClose}>
      <div className={'modal' + (tab === 'spec' ? ' wide' : '')} onClick={(e) => e.stopPropagation()}>
        <div className="modal-head"><h3 style={{ margin: 0, fontSize: 15 }}>Add widget to {page.title}</h3>
          <div className="row" style={{ gap: 6, marginLeft: 16 }}>
            {TABS.map(([k, label]) => <button key={k} className={'chip' + (tab === k ? ' active' : '')} onClick={() => setTab(k)}>{label}</button>)}
          </div>
          <button className="icon-btn" style={{ marginLeft: 'auto' }} onClick={onClose}><Icon name="x" /></button></div>
        <div className="modal-body">
          {tab === 'library' && <LibraryTab pageId={page.id} onDone={onClose} />}
          {tab === 'spec' && <SpecWorkbench page={page} pageParams={pageParams} onPlace={placeSaved} />}
          {tab === 'builtin' && <BuiltInTab pageId={page.id} onDone={onClose} />}
        </div>
      </div>
    </div>
  );
}

export function WidgetGrid({ page, pageParams }) {
  return (
    <div className="grid">
      {page.widgets.map((w) => {
        const reg = REGISTRY[w.type];
        if (!reg) {
          return <div key={w.id} className="card" style={{ gridColumn: 'span ' + (w.span || 12) }}><div className="empty">Unknown widget type “{w.type}”</div></div>;
        }
        const C = reg.component;
        return (
          <WidgetFrame key={w.id + JSON.stringify(w.config) + w.span + w.title} pageId={page.id} widget={w} bare={reg.bare}>
            <C config={w.config || {}} widget={w} pageId={page.id} pageParams={pageParams} />
          </WidgetFrame>
        );
      })}
      {page.widgets.length === 0 && (
        <div className="card" style={{ gridColumn: 'span 12' }}>
          <div className="empty">This page is empty. Add a widget to get started.</div>
        </div>
      )}
    </div>
  );
}

/** The page's client picker, in the top bar (it changes what the whole page is about). */
function ParamPickers({ params }) {
  return params.defs.map((p) => (
    <label key={p.name} className="row" style={{ gap: 6 }}>
      <span className="label">{p.label}</span>
      <select value={params.values[p.name] || ''} onChange={(e) => params.set(p.name, e.target.value)} style={{ minWidth: 190, maxWidth: 260 }}>
        {(params.options[p.name] || []).map((o) => <option key={o.value} value={o.value}>{o.label}</option>)}
      </select>
    </label>
  ));
}

/** The page's lens, under the top bar: its period and filters (a filter's empty choice means all), and a reset. */
function ContextBar({ params }) {
  if (!params.period && !params.filters.length) return null;
  const active = params.filters.filter((f) => params.values[f.dimension]);
  const clear = () => active.forEach((f) => params.set(f.dimension, ''));
  return (
    <div className="context-bar">
      {params.period && (
        <label className="context-field">
          <span className="label">{params.period.label || 'Period'}</span>
          <select value={params.values.period || ''} onChange={(e) => params.set('period', e.target.value === params.period.defaultOption ? '' : e.target.value)}>
            {params.period.options.map((o) => <option key={o.id} value={o.id}>{o.label}</option>)}
          </select>
        </label>
      )}
      {params.filters.map((f) => (
        <label key={f.dimension} className="context-field">
          <span className="label">{f.label}</span>
          <select value={params.values[f.dimension] || ''} onChange={(e) => params.set(f.dimension, e.target.value)}
            className={params.values[f.dimension] ? 'active-filter' : undefined}>
            <option value="">All</option>
            {(params.filterOptions[f.dimension] || []).map((v) => <option key={v} value={v}>{v}</option>)}
          </select>
        </label>
      ))}
      {active.length > 0 && <button className="btn small ghost" onClick={clear}>Clear filters</button>}
    </div>
  );
}

export default function DynamicPage() {
  const { pageId = 'command-center' } = useParams();
  const { layout, deletePage, resetLayout, setAssistantOpen } = usePlatform();
  const [adding, setAdding] = useState(false);
  const [assisting, setAssisting] = useState(false);
  // The chat stays mounted once opened, so going back to the dashboard keeps the conversation.
  const [chatOpened, setChatOpened] = useState(false);
  const nav = useNavigate();
  const location = useLocation();
  const page = layout?.pages.find((p) => p.id === pageId);
  const params = usePageParams(page);
  const showChat = assisting && params.ready && Boolean(page);
  // a conversation opened from Skills on another page arrives in the navigation state
  const openSessionId = location.state?.assistantSession;
  useEffect(() => { if (openSessionId) { setAssisting(true); setChatOpened(true); } }, [openSessionId]);
  const openElsewhere = (targetPage, sessionId, sessionParams) => {
    const target = layout.pages.find((p) => p.id === targetPage);
    const q = new URLSearchParams();
    (target?.params || []).forEach((p) => { if (sessionParams?.[p.name]) q.set(p.name, sessionParams[p.name]); });
    nav('/page/' + targetPage + (q.toString() ? '?' + q : ''), { state: { assistantSession: sessionId } });
  };
  useEffect(() => { setAssistantOpen(showChat); }, [showChat, setAssistantOpen]);
  useEffect(() => () => setAssistantOpen(false), [setAssistantOpen]);
  if (!layout) return <Loading label="Loading layout" />;
  if (!page) {
    return (
      <>
        <TopBar title="Page not found" />
        <div className="content"><div className="empty">No page “{pageId}”.{' '}
          {layout.pages.length > 0 && <button className="btn small" onClick={() => nav('/page/' + layout.pages[0].id)}>Go home</button>}{' '}
          <button className="btn small" title="Restore the default pages" onClick={async () => { await resetLayout(); nav('/'); }}><Icon name="refresh" size={12} /> Reset layout</button></div></div>
      </>
    );
  }
  const actions = (
    <>
      <ParamPickers params={params} />
      <button className="btn" onClick={() => { setAssisting(true); setChatOpened(true); }} disabled={!params.ready}
        title="Describe a widget and the assistant builds it for you to approve"><Icon name="spark" size={13} /> Assistant</button>
      <button className="btn" onClick={() => setAdding(true)} disabled={!params.ready}><Icon name="plus" size={13} /> Widget</button>
      <button className="btn danger" title="Delete this page" onClick={async () => {
        const msg = page.system
          ? `Delete the system page “${page.title}”? It can be restored by resetting the layout.`
          : `Delete “${page.title}”?`;
        if (!window.confirm(msg)) return;
        const l = await deletePage(page.id, page.system);
        nav(l?.pages?.length ? '/page/' + l.pages[0].id : '/');
      }}><Icon name="trash" size={13} /> Page</button>
      {page.id === 'command-center' && <button className="btn ghost" title="Reset the whole layout to defaults" onClick={() => resetLayout()}><Icon name="refresh" size={13} /></button>}
    </>
  );
  return (
    <>
      {!showChat && (
        <div>
          <TopBar title={page.title} sub={page.description} actions={actions} badge={page.createdBy && page.createdBy !== 'system' ? page.createdBy : null} />
          <ContextBar params={params} />
          <div className="content">
            {params.error && <div className="error-box">⚠ Could not load page choices: {params.error.message}</div>}
            {params.ready ? <WidgetGrid page={page} pageParams={params.values} /> : !params.error && <Loading label="Loading page" />}
          </div>
          {adding && <AddWidgetModal page={page} pageParams={params.values} onClose={() => setAdding(false)} />}
        </div>
      )}
      {chatOpened && params.ready && (
        <div className="chat-host" hidden={!showChat}>
          <AssistantChat page={page} pageParams={params.values} toolbar={<ParamPickers params={params} />} onClose={() => setAssisting(false)}
            openSessionId={openSessionId} onElsewhere={openElsewhere} />
        </div>
      )}
    </>
  );
}
