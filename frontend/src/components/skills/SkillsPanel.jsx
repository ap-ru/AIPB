import { useEffect, useState } from 'react';
import { assistant } from '../../lib/assistant.js';
import { skills } from '../../lib/skills.js';
import { usePlatform } from '../../lib/platform.jsx';
import SpecView from '../../widgets/spec/SpecView.jsx';
import Icon from '../Icon.jsx';

const STATUS = {
  pending: ['Pending approval', 'amber'],
  changes_requested: ['Feedback & revise', 'pink'],
  rejected: ['Rejected', 'red'],
  approved: ['Approved', 'green'],
  personal: ['Personal · not shared', 'violet'],
};

const SOURCE = { stock: 'Stock', approved: 'Approved', restore: 'Restored', migrated: 'Existing' };

const EMPTY = {
  mine: 'Nothing in progress. Widgets you build go here until they are approved.',
  review: 'Nothing waiting for approval.',
  approved: 'No approved widgets yet.',
  stock: 'No stock widgets.',
};

function when(t) {
  return t ? new Date(t).toLocaleString('en-US', { month: 'short', day: 'numeric', hour: '2-digit', minute: '2-digit' }) : '';
}

function StatusTag({ status }) {
  const [label, tone] = STATUS[status] || [status, ''];
  return <span className={'tag ' + tone}>{label}</span>;
}

/** A spec's live run (the validator's preview), drawn by the same primitives a placed widget uses. */
function Preview({ title, sub, data, error }) {
  const p = data?.preview;
  return (
    <div className="card skill-preview">
      <div className="card-head"><h3>{title}</h3>{sub}</div>
      <div className="card-body spec-body">
        {error ? <div className="error-box">⚠ {error}</div>
          : !data ? <div className="muted" style={{ fontSize: 12 }}>Loading preview…</div>
          : data.errors?.length ? <div className="error-box">⚠ {data.errors.map((e) => e.message).join(' · ')}</div>
          : p?.rows?.length ? <SpecView view={data.view} columns={p.columns} rows={p.rows} truncated={p.truncated} total={p.count} narrative={p.narrative} />
          : <div className="empty soft">No rows for these sample values.</div>}
      </div>
    </div>
  );
}

function Item({ item, showVersion, onOpen }) {
  return (
    <button className="skill-item" onClick={() => onOpen(item)}>
      <div style={{ minWidth: 0, flex: 1 }}>
        <div className="spec-list-title">{item.title}</div>
        <div className="spec-list-subtitle">{item.description}</div>
        {item.comment && item.status !== 'pending' && <div className="skill-comment">“{item.comment}”</div>}
      </div>
      <div className="skill-tags">
        {(item.kind === 'submission' || item.status === 'personal') && <StatusTag status={item.status} />}
        {item.kind === 'submission' && item.baseVersion != null && <span className="tag">change to v{item.baseVersion}</span>}
        {item.kind === 'submission' && item.baseVersion == null && <span className="tag">new</span>}
        {showVersion && item.kind === 'widget' && item.status !== 'personal' && <span className="tag">v{item.version}</span>}
        {item.pendingChange && <span className="tag amber">change pending</span>}
        <span className="tag">{item.primitive}</span>
      </div>
      <Icon name="right" size={14} />
    </button>
  );
}

/** Reviewer only: every version of a widget, with a preview and Restore for each older one. */
function Versions({ widget, pageParams, onRestored }) {
  const [list, setList] = useState(null);
  const [open, setOpen] = useState(null);
  const [detail, setDetail] = useState(null);
  const [problem, setProblem] = useState(null);
  const [busy, setBusy] = useState(false);
  const load = () => skills.versions(widget.id).then(setList).catch((e) => setProblem(e.message));
  useEffect(() => { load(); }, [widget.id, widget.version]); // eslint-disable-line react-hooks/exhaustive-deps
  const show = (v) => {
    if (open === v) { setOpen(null); return; }
    setOpen(v); setDetail(null);
    skills.version(widget.id, v, pageParams).then(setDetail).catch((e) => setDetail({ failed: e.message }));
  };
  const restore = async (v) => {
    if (!window.confirm(`Make v${v} current again? It is published as v${widget.version + 1} and every page showing this widget updates.`)) return;
    setBusy(true); setProblem(null);
    try { await skills.restore(widget.id, v); setOpen(null); await onRestored(); }
    catch (e) { setProblem(e.message); } finally { setBusy(false); }
  };
  return (
    <div className="pv-section">
      <div className="label">Version history</div>
      {problem && <div className="error-box">⚠ {problem}</div>}
      {!list ? <div className="muted" style={{ fontSize: 12 }}>Loading…</div> : (
        <div className="skill-versions">
          {list.map((v) => (
            <div key={v.version}>
              <div className="skill-version">
                <b>v{v.version}</b>
                {v.current && <span className="tag green">current</span>}
                <span className="tag">{SOURCE[v.source] || v.source}{v.restoredFrom ? ` from v${v.restoredFrom}` : ''}</span>
                <span className="muted" style={{ fontSize: 12, minWidth: 0, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>{v.title}</span>
                <span className="dim" style={{ fontSize: 11, marginLeft: 'auto', whiteSpace: 'nowrap' }}>{when(v.createdAt)}</span>
                <button className="btn small ghost" onClick={() => show(v.version)}>{open === v.version ? 'Hide' : 'Preview'}</button>
                {!v.current && <button className="btn small" disabled={busy} onClick={() => restore(v.version)}>Restore</button>}
              </div>
              {open === v.version && <Preview title={`v${v.version} · ${v.title}`} data={detail?.failed ? null : detail} error={detail?.failed} />}
            </div>
          ))}
        </div>
      )}
    </div>
  );
}

/**
 * An approved or stock widget: preview, Add to page, Modify; for a Reviewer, its versions. A personal widget not shared
 * yet: preview, Add to page (only its owner sees it there) and Submit for review.
 */
function WidgetDetail({ item, page, pageParams, onModify, onChanged }) {
  const { can, addWidget } = usePlatform();
  const [widget, setWidget] = useState(item);
  const [data, setData] = useState(null);
  const [problem, setProblem] = useState(null);
  const [note, setNote] = useState(null);
  const loadPreview = () => {
    setData(null);
    skills.preview(widget.id, pageParams).then(setData).catch((e) => setData({ errors: [{ message: e.message }] }));
  };
  useEffect(loadPreview, [widget.id, widget.version]); // eslint-disable-line react-hooks/exhaustive-deps
  const add = async () => {
    setProblem(null); setNote(null);
    try {
      await addWidget(page.id, { type: 'spec', title: widget.title, span: 0, config: { definitionId: widget.id } });
      setNote(`Added to ${page.title}.`);
    } catch (e) { setProblem(e.message); }
  };
  const [shareNote, setShareNote] = useState('');
  const personal = widget.scope === 'personal';
  const share = async () => {
    setProblem(null); setNote(null);
    try { await skills.share(widget.id, shareNote.trim() || null); setNote('Shared for review.'); onChanged(); }
    catch (e) { setProblem(e.message); }
  };
  const restored = async () => {
    const r = await skills.list();
    const fresh = r.sections.flatMap((s) => s.items).find((i) => i.kind === 'widget' && i.id === widget.id);
    if (fresh) setWidget(fresh);
    onChanged();
  };
  return (
    <div className="stack" style={{ gap: 14 }}>
      <div className="row" style={{ flexWrap: 'wrap' }}>
        {personal ? <span className="tag violet">Personal · only you</span> : <span className="tag green">Approved</span>}
        {!personal && <span className="tag">v{widget.version}</span>}
        <span className="tag">{widget.origin === 'stock' ? 'Stock' : 'Built in the app'}</span>
        {widget.pendingChange && <span className="tag amber">A change is waiting for approval</span>}
      </div>
      <Preview title={widget.title} data={data} />
      {problem && <div className="error-box">⚠ {problem}</div>}
      {note && <div className="pv-placed"><Icon name="flag" size={13} /> {note}</div>}
      <div className="row">
        <button className="btn primary small" onClick={add}><Icon name="plus" size={12} /> Add to {page.title}</button>
        {!personal && (
          <button className="btn small" onClick={() => onModify(widget)} disabled={Boolean(widget.pendingChange)}
            title={widget.pendingChange ? 'A change to this widget is already waiting for approval' : 'Change it with the assistant; the new version goes for approval'}>
            <Icon name="edit" size={12} /> Modify</button>
        )}
      </div>
      {personal && (
        <div className="skill-decide">
          <div className="muted" style={{ fontSize: 12 }}>In your personal library: only you see it on pages. Submit it so a Reviewer can approve it for everyone.</div>
          <textarea rows={2} value={shareNote} onChange={(e) => setShareNote(e.target.value)} placeholder="Note for the Reviewer (optional)" />
          <div className="row"><button className="btn primary small" onClick={share}>Submit for review</button></div>
        </div>
      )}
      {can.canReview && !personal && <Versions widget={widget} pageParams={pageParams} onRestored={restored} />}
    </div>
  );
}

/** A submission: proposal (beside the current version, for a change), history, and the actions the role allows. */
function SubmissionDetail({ item, pageParams, onOpenSession, onChanged }) {
  const { can } = usePlatform();
  const [sub, setSub] = useState(null);
  const [problem, setProblem] = useState(null);
  const [comment, setComment] = useState('');
  const [busy, setBusy] = useState(false);
  const [turns, setTurns] = useState(null);
  const load = () => skills.submission(item.id, pageParams).then(setSub).catch((e) => setProblem(e.message));
  useEffect(() => { load(); }, [item.id]); // eslint-disable-line react-hooks/exhaustive-deps
  const decide = async (action) => {
    setBusy(true); setProblem(null);
    try { await skills[action](item.id, comment.trim() || null); setComment(''); await load(); onChanged(); }
    catch (e) { setProblem(e.message); } finally { setBusy(false); }
  };
  const showConversation = () => {
    if (turns) { setTurns(null); return; }
    assistant.session(sub.sessionId).then((s) => setTurns(s.turns)).catch((e) => setProblem(e.message));
  };
  if (!sub) return problem ? <div className="error-box">⚠ {problem}</div> : <div className="muted" style={{ fontSize: 12 }}>Loading…</div>;
  const pending = sub.status === 'pending';
  const reviewer = can.canReview;
  return (
    <div className="stack" style={{ gap: 14 }}>
      <div className="row" style={{ flexWrap: 'wrap' }}>
        <StatusTag status={sub.status} />
        <span className="tag">{sub.baseVersion != null ? `Change to v${sub.baseVersion}` : 'New widget'}</span>
        {sub.approvedVersion && <span className="tag green">became v{sub.approvedVersion}</span>}
        <span className="tag">from the {sub.origin}</span>
      </div>
      {sub.current ? (
        <div className="skill-compare">
          <Preview title={sub.current.title} sub={<span className="tag">current · v{sub.current.version}</span>} data={sub.current} />
          <Preview title={sub.title} sub={<span className="tag amber">proposed</span>} data={sub} />
        </div>
      ) : <Preview title={sub.title} data={sub} />}
      {sub.description && <div className="muted" style={{ fontSize: 13 }}>{sub.description}</div>}

      <div className="pv-section">
        <div className="label">History</div>
        <ul className="skill-history">
          {sub.history.map((e, i) => (
            <li key={i}><b>{e.type === 'sent_back' ? 'feedback & revise' : e.type.replace('_', ' ')}</b> <span className="dim">by {e.role} · {when(e.at)}</span>
              {e.comment && <div className="skill-comment">“{e.comment}”</div>}</li>
          ))}
        </ul>
      </div>

      {sub.sessionId && (
        <div className="pv-section">
          <button className="pv-why" onClick={showConversation}>{turns ? 'Hide conversation' : 'Show the conversation'}</button>
          {turns && (
            <div className="skill-turns">
              {turns.map((t, i) => <div key={i} className={'skill-turn ' + t.role}><b>{t.role === 'user' ? 'Builder' : t.role === 'reviewer' ? 'Reviewer' : 'Assistant'}:</b> {t.text}</div>)}
            </div>
          )}
        </div>
      )}

      {problem && <div className="error-box">⚠ {problem}</div>}

      {reviewer && pending && (
        <div className="skill-decide">
          <textarea rows={3} value={comment} onChange={(e) => setComment(e.target.value)}
            placeholder="Comment - required for feedback & revise (what to change) or reject (why)" />
          <div className="row">
            <button className="btn primary small" disabled={busy} onClick={() => decide('approve')}>Approve</button>
            <button className="btn small" disabled={busy || !comment.trim()} onClick={() => decide('sendBack')}>Feedback &amp; revise</button>
            <button className="btn small danger" disabled={busy || !comment.trim()} onClick={() => decide('reject')}>Reject</button>
          </div>
        </div>
      )}
      {!reviewer && sub.sessionId && (sub.status === 'changes_requested' || pending) && (
        <div className="row">
          <button className={'btn small' + (sub.status === 'changes_requested' ? ' primary' : '')} onClick={() => onOpenSession(sub.sessionId, sub.pageId)}>
            <Icon name="spark" size={12} /> {sub.status === 'changes_requested' ? 'Open chat to revise' : 'Open chat'}</button>
          {pending && <span className="muted" style={{ fontSize: 12 }}>Waiting for a Reviewer. You can still change it from the chat.</span>}
        </div>
      )}
      {!reviewer && !sub.sessionId && sub.status === 'changes_requested' && (
        <div className="muted" style={{ fontSize: 12 }}>Made in the workbench: revise it there (Widget → Write a spec) and submit it again with the same id.</div>
      )}
      {sub.status === 'rejected' &&<div className="muted" style={{ fontSize: 12 }}>Rejected widgets are closed. Start a new chat to build another.</div>}
    </div>
  );
}

/**
 * The Skills window: every widget the role can use or act on. A builder sees their widgets in progress, then the
 * approved and stock widgets; a Reviewer sees what waits for approval, then approved, then stock.
 */
export default function SkillsPanel({ page, pageParams, onClose, onOpenSession, onModify, onChanged }) {
  const { role, can } = usePlatform();
  const [data, setData] = useState(null);
  const [problem, setProblem] = useState(null);
  const [selected, setSelected] = useState(null);
  const load = () => skills.list().then(setData).catch((e) => setProblem(e.message));
  useEffect(() => { setSelected(null); load(); }, [role]); // eslint-disable-line react-hooks/exhaustive-deps
  const changed = () => { load(); onChanged?.(); };
  return (
    <div className="overlay" onClick={onClose}>
      <div className="modal wide skills-modal" onClick={(e) => e.stopPropagation()}>
        <div className="modal-head">
          {selected && <button className="btn ghost small" onClick={() => { setSelected(null); load(); }}><Icon name="left" size={14} /> Skills</button>}
          <h3 style={{ margin: 0, fontSize: 15 }}>{selected ? selected.title : 'Skills'}</h3>
          {!selected && <span className="tag violet">{can.label || role}</span>}
          <button className="icon-btn" style={{ marginLeft: 'auto' }} onClick={onClose} aria-label="Close"><Icon name="x" /></button>
        </div>
        <div className="modal-body">
          {problem && <div className="error-box">⚠ {problem}</div>}
          {selected ? (
            selected.kind === 'widget'
              ? <WidgetDetail item={selected} page={page} pageParams={pageParams} onModify={onModify} onChanged={changed} />
              : <SubmissionDetail item={selected} pageParams={pageParams} onOpenSession={onOpenSession} onChanged={changed} />
          ) : !data ? <div className="muted" style={{ fontSize: 12 }}>Loading…</div> : (
            <div className="stack" style={{ gap: 18 }}>
              {data.sections.map((s) => (
                <section key={s.key}>
                  <div className="skills-section-head">
                    <div className="label">{s.title}</div>
                    <span className="dim" style={{ fontSize: 11 }}>{s.items.length}</span>
                  </div>
                  {s.items.length
                    ? <div className="skill-list">{s.items.map((it) => <Item key={it.kind + it.id} item={it} showVersion onOpen={setSelected} />)}</div>
                    : <div className="empty soft">{EMPTY[s.key]}</div>}
                </section>
              ))}
            </div>
          )}
        </div>
      </div>
    </div>
  );
}
