import { useEffect, useLayoutEffect, useRef, useState } from 'react';
import { assistant } from '../../lib/assistant.js';
import { skills, CUSTOM_WIDGET, KIND_LABELS } from '../../lib/skills.js';
import { usePlatform, LAYOUT_ID } from '../../lib/platform.jsx';
import SpecView from '../../widgets/spec/SpecView.jsx';
import Icon from '../Icon.jsx';

/** The widget stages, until a session tells us its kind's own (session.stages). */
const STEPS = [['intake', 'Understanding'], ['build', 'Finding the data'], ['review', 'Checking the numbers'], ['present', 'Designing']];

const SUGGESTIONS = {
  client: ['Deposits by product for this client', 'Loan exposure vs. committed limits', 'Monthly balance trend over the last year', 'Open opportunities by stage'],
  book: ['Top 10 clients by total deposits', 'Pipeline by stage', 'Clients with maturing loans this quarter', 'Deposit mix across the book'],
};

function Avatar() {
  return <div className="msg-avatar" aria-hidden="true"><Icon name="spark" size={14} stroke={2} /></div>;
}

/** Live progress while a stage pipeline runs: the current step, the steps so far, and the latest activity. */
function Thinking({ stages, activity, ack, labels = STEPS }) {
  const seen = new Set(stages.map((s) => s.stage));
  const steps = labels.filter(([k]) => k !== 'review' || seen.has('review'));
  const status = (k) => [...stages].reverse().find((s) => s.stage === k)?.status || 'todo';
  const current = [...steps].reverse().find(([k]) => status(k) === 'started') || steps.find(([k]) => status(k) === 'todo');
  return (
    <div className="msg assistant">
      <Avatar />
      <div className="msg-main">
        {ack && <div className="msg-text">{ack}</div>}
        <div className="thinking">
          <div className="thinking-now"><span className="thinking-pulse" />{current ? current[1] + '…' : 'Working…'}</div>
          <ol className="thinking-steps">
            {steps.map(([k, label]) => <li key={k} className={status(k)}>{label}</li>)}
          </ol>
          {activity.slice(-4).map((a, i) => <div key={i} className="thinking-activity">{a}</div>)}
        </div>
      </div>
    </div>
  );
}

function List({ title, items, render }) {
  if (!items || !items.length) return null;
  return (
    <div className="pv-section">
      <div className="label">{title}</div>
      <ul>{items.map((it, i) => <li key={i}>{render ? render(it) : it}</li>)}</ul>
    </div>
  );
}

function reviewTag(proposal, reviewState) {
  if (proposal.kind === 'reuse') return <span className="tag">From library</span>;
  if (reviewState === 'passed') return <span className="tag green">Verified</span>;
  if (reviewState === 'failed') return <span className="tag red">Not verified</span>;
  return <span className="tag">Unreviewed</span>;
}

function ReviewDetail({ review }) {
  if (!review || review.state === 'none' || review.state === 'library') return null;
  return (
    <>
      <div className="pv-section"><div className="label">Independent review</div>{review.summary}</div>
      <List title="Checks" items={review.independentChecks}
        render={(c) => <>{c.figure}: {c.method} - widget {c.expected}, recomputed {c.actual} <span className={'tag ' + (c.match ? 'green' : 'red')}>{c.match ? 'match' : 'mismatch'}</span></>} />
      <List title="Findings" items={review.issues} render={(i) => <><span className={'tag ' + (i.severity === 'blocking' ? 'red' : 'amber')}>{i.severity}</span> {i.description}</>} />
      {review.note && <div className="pv-section muted">{review.note}</div>}
    </>
  );
}

/** After a new widget is added: offer to publish it to the global library, where a Reviewer approves it for everyone. */
function SubmitForReview({ submission, busy, onSubmit }) {
  const [note, setNote] = useState('');
  if (submission === 'pending') {
    return <div className="pv-actions"><span className="tag amber">Pending review</span>
      <span className="pv-hint">In your personal library until a Reviewer approves it.</span></div>;
  }
  return (
    <div className="pv-submit">
      <div className="pv-submit-title">Share it with everyone?</div>
      <div className="muted" style={{ fontSize: 12 }}>It’s in your personal library. Submit it to the global library and a Reviewer will check it before it’s available to all bankers.</div>
      <textarea value={note} onChange={(e) => setNote(e.target.value)} rows={2} placeholder="What should the reviewer know? (optional)" />
      <div className="pv-actions">
        <button className="btn primary small" disabled={busy} onClick={() => onSubmit(note)}>Submit to global library</button>
        <span className="pv-hint">or keep it personal</span>
      </div>
    </div>
  );
}

/** A proposal, previewed in the same card chrome a placed widget gets, with the approval actions under it. */
function Proposal({ proposal, reviewState, placed, submission, busy, page, onAdd, onVerify, onView, onSubmit }) {
  const [confirm, setConfirm] = useState(false);
  const [open, setOpen] = useState(false);
  const failed = reviewState === 'failed' && proposal.kind === 'new';
  const p = proposal.preview || {};
  return (
    <div className="pv">
      <div className="pv-card card">
        <div className="card-head"><h3>{proposal.title}</h3></div>
        <div className="card-body spec-body">
          {proposal.description && <div className="spec-sub"><span>{proposal.description}</span></div>}
          {p.rows && p.rows.length
            ? <SpecView view={p.view} columns={p.columns} rows={p.rows} truncated={p.truncated} total={p.count} />
            : <div className="empty soft">No rows for the selected {page.params?.length ? page.params[0].label.toLowerCase() : 'selection'}.</div>}
        </div>
      </div>
      <div className="pv-meta">
        {reviewTag(proposal, reviewState)}
        <span className="tag">{proposal.primitive}</span>
        <button className="pv-why" onClick={() => setOpen((o) => !o)} aria-expanded={open}>
          {open ? 'Hide' : 'How it’s calculated'} <Icon name={open ? 'up' : 'down'} size={11} />
        </button>
      </div>
      {open && (
        <div className="pv-detail">
          <List title="What it shows" items={proposal.whatItShows} render={(w) => <><b>{w.figure}</b>: {w.definition} <span className="dim">({w.source})</span></>} />
          <List title="Assumptions" items={proposal.assumptions} />
          <List title="Checks passed" items={proposal.checks} />
          <List title="Warnings" items={proposal.warnings} />
          <ReviewDetail review={proposal.review} />
          {(!proposal.review || proposal.review.state === 'none') && proposal.kind === 'new' &&
            <div className="pv-section muted">Not independently reviewed. Verify the numbers to have them recomputed separately.</div>}
        </div>
      )}
      {placed ? (
        <>
          <div className="pv-actions">
            <span className="pv-placed"><Icon name="flag" size={13} /> Added to {page.title}</span>
            <button className="btn small" onClick={onView}>View on page <Icon name="right" size={12} /></button>
          </div>
          {proposal.kind === 'new' && <SubmitForReview submission={submission} busy={busy} onSubmit={onSubmit} />}
        </>
      ) : (
        <>
          {failed && (
            <label className="pv-confirm">
              <input type="checkbox" checked={confirm} onChange={(e) => setConfirm(e.target.checked)} />
              I understand the review could not verify these numbers.
            </label>
          )}
          <div className="pv-actions">
            <button className="btn primary small" disabled={busy || (failed && !confirm)} onClick={() => onAdd(confirm)}>
              <Icon name="plus" size={12} /> Add to {page.title}</button>
            {proposal.kind === 'new' && reviewState !== 'passed' && (
              <button className="btn small" disabled={busy} onClick={onVerify} title="An independent check of the numbers (uses extra tokens)">Verify numbers</button>
            )}
            <span className="pv-hint">or reply with changes</span>
          </div>
        </>
      )}
    </div>
  );
}

/**
 * Which Skills Studio template the conversation starts from (Custom widget by default), and the template's inputs.
 * Only templates that fit the page are offered.
 */
function TemplatePicker({ options, template, inputs, onTemplate, onInputs, disabled }) {
  if (!options || options.length < 2) return null;
  return (
    <div className="tpl-picker">
      <div className="tpl-row">
        <span className="label">Start from</span>
        {options.map((t) => (
          <button key={t.id} className={'chat-chip' + (template?.id === t.id ? ' active' : '')} disabled={disabled}
            onClick={() => onTemplate(t)} title={t.summary}>
            <Icon name={t.icon} size={12} /> {t.title}
          </button>
        ))}
      </div>
      {template && template.id !== CUSTOM_WIDGET && (
        <div className="tpl-summary">
          <span>{template.summary}</span>
          {template.inputs.map((i) => (
            <label key={i.name} className="tpl-input">
              <span>{i.label}</span>
              {i.type === 'enum'
                ? <select value={inputs[i.name] || ''} disabled={disabled} onChange={(e) => onInputs({ ...inputs, [i.name]: e.target.value })}>
                    <option value="">{i.required ? 'Choose…' : 'Any'}</option>
                    {i.options.map((o) => <option key={o} value={o}>{o}</option>)}
                  </select>
                : <input value={inputs[i.name] || ''} disabled={disabled} onChange={(e) => onInputs({ ...inputs, [i.name]: e.target.value })} />}
            </label>
          ))}
        </div>
      )}
    </div>
  );
}

function Details({ sessionId }) {
  const [runs, setRuns] = useState(null);
  useEffect(() => { assistant.runs(sessionId).then(setRuns).catch(() => setRuns([])); }, [sessionId]);
  if (!runs) return <div className="muted" style={{ fontSize: 12 }}>Loading…</div>;
  const cost = runs.reduce((s, r) => s + (r.costUsd || 0), 0);
  return (
    <div className="as-details">
      <div className="muted" style={{ fontSize: 11 }}>{runs.length} stage runs · est. ${cost.toFixed(3)}</div>
      {runs.map((r, i) => (
        <div key={i} className="as-run">
          <div className="row" style={{ gap: 6 }}>
            <b>{r.stage}</b><span className={'tag ' + (r.outcome === 'ok' ? 'green' : 'red')}>{r.outcome}</span>
            <span className="mono dim">{r.model} · {r.effort} · {(r.ms / 1000).toFixed(1)}s · {r.calls} call{r.calls === 1 ? '' : 's'}</span>
          </div>
          <div className="mono dim">in {r.inputTokens} · out {r.outputTokens} · cache read {r.cacheReadTokens} · write {r.cacheWriteTokens} · ${(r.costUsd || 0).toFixed(4)}</div>
          {r.toolCalls?.map((t, j) => <div key={j} className="mono" style={{ fontSize: 11 }}>{t.ok ? '✓' : '✗'} {t.summary}</div>)}
          <div className="dim" style={{ fontSize: 11 }}>Context: {r.manifest?.map((m) => m.block).join(', ')}</div>
          {r.error && <div className="error-box">{r.error}</div>}
        </div>
      ))}
    </div>
  );
}

function Composer({ value, onChange, onSend, running, disabled, autoFocus, placeholder }) {
  const ref = useRef(null);
  useLayoutEffect(() => {
    const el = ref.current;
    if (!el) return;
    el.style.height = 'auto';
    const h = Math.ceil(el.scrollHeight);
    el.style.height = Math.min(h, 200) + 'px';
    // only scroll inside the box once it reaches its max height; a sub-pixel overflow must not make it scrollable
    el.style.overflowY = h > 200 ? 'auto' : 'hidden';
  }, [value]);
  useEffect(() => { if (autoFocus) ref.current?.focus(); }, [autoFocus]);
  useEffect(() => { if (!running) ref.current?.focus(); }, [running]);
  const canSend = !running && !disabled && value.trim();
  return (
    <div className={'composer' + (disabled ? ' disabled' : '')} onClick={() => ref.current?.focus()}>
      <textarea ref={ref} rows={1} value={value} placeholder={placeholder} disabled={disabled}
        onChange={(e) => onChange(e.target.value)}
        onKeyDown={(e) => { if (e.key === 'Enter' && !e.shiftKey && !e.nativeEvent.isComposing) { e.preventDefault(); if (canSend) onSend(); } }} />
      <button className="composer-send" onClick={onSend} disabled={!canSend} aria-label="Send">
        {running ? <span className="composer-spin" /> : <Icon name="up" size={16} stroke={2.2} />}
      </button>
    </div>
  );
}

/**
 * The widget assistant as a full-page conversation (Builders and Reviewers). It proposes; the user approves before anything
 * is added, to their personal library and the page, and can then submit it to the global library for review.
 * Kept mounted while hidden, so going back to the dashboard and returning keeps the conversation.
 */
export default function AssistantChat({ page, pageParams, start, toolbar, onClose }) {
  const { setLayout } = usePlatform();
  const [enabled, setEnabled] = useState(null);
  const [session, setSession] = useState(null);
  const [text, setText] = useState('');
  const [pending, setPending] = useState(null);
  const [running, setRunning] = useState(false);
  const [stages, setStages] = useState([]);
  const [activity, setActivity] = useState([]);
  const [ack, setAck] = useState(null);
  const [problem, setProblem] = useState(null);
  const [showDetails, setShowDetails] = useState(false);
  // the template the next conversation starts from (null: Custom widget), its inputs, and the templates this page offers
  const [template, setTemplate] = useState(null);
  const [inputs, setInputs] = useState({});
  const [options, setOptions] = useState(null);
  const scroller = useRef(null);

  useEffect(() => { assistant.status().then((s) => setEnabled(s.enabled)).catch(() => setEnabled(false)); }, []);
  const reset = () => { setSession(null); setStages([]); setActivity([]); setAck(null); setProblem(null); setPending(null); setShowDetails(false); };
  useEffect(() => { reset(); setTemplate(null); setInputs({}); }, [page.id]);
  useEffect(() => { setOptions(null); skills.templates(page.id).then(setOptions).catch(() => setOptions([])); }, [page.id]);
  // opened from the Skills Studio: a fresh conversation with the chosen template
  useEffect(() => {
    if (!start) return;
    reset();
    setInputs(start.inputs || {});
    skills.template(start.templateId).then(setTemplate).catch((e) => setProblem(e.message));
  }, [start]);
  useEffect(() => {
    const el = scroller.current;
    if (el) el.scrollTo({ top: el.scrollHeight, behavior: 'smooth' });
  }, [session, activity, pending, problem]);

  const refresh = async (id) => setSession(await assistant.session(id));

  const onEvent = (event, data) => {
    if (event === 'stage') setStages((s) => [...s.filter((x) => !(x.stage === data.stage && data.status !== 'started' && x.status === 'started')), data]);
    else if (event === 'activity') setActivity((a) => [...a, data.text]);
    else if (event === 'message' && data.kind === 'ack') setAck(data.text);
    else if (event === 'error') setProblem(data.message);
  };

  /** Runs {@code call} on the session, creating one first; {@code fresh} starts a new one (with {@code tpl}, e.g. after a redirect). */
  const run = async (call, { fresh = false, tpl = template, tplInputs = inputs } = {}) => {
    setRunning(true); setProblem(null); setStages([]); setActivity([]); setAck(null);
    try {
      let s = fresh ? null : session;
      if (!s) {
        const chosen = tpl && tpl.id !== CUSTOM_WIDGET ? tpl.id : null;
        s = await assistant.create(LAYOUT_ID, page.id, pageParams, chosen, chosen ? tplInputs : null);
        setSession(s);
      }
      await call(s.id);
      await refresh(s.id);
    } catch (e) {
      setProblem(e.message);
    } finally { setRunning(false); setPending(null); setAck(null); }
  };

  const send = (raw = text, opts) => {
    const t = raw.trim();
    if (!t || running) return;
    setText('');
    setPending(t);
    run((id) => assistant.message(id, t, pageParams, onEvent), opts);
  };

  /** The assistant suggested another template: start over with it and ask the same thing again. */
  const switchTo = (templateId, question) => {
    const t = (options || []).find((o) => o.id === templateId);
    if (!t) return;
    reset();
    setTemplate(t);
    setInputs({});
    send(question, { fresh: true, tpl: t, tplInputs: {} });
  };

  const verify = () => { setPending('Verify the numbers.'); run((id) => assistant.verify(id, onEvent)); };

  const add = async (confirmUnverified) => {
    setRunning(true); setProblem(null);
    try {
      const r = await assistant.approve(session.id, confirmUnverified);
      if (r.layout) setLayout(r.layout);
      await refresh(session.id);
    } catch (e) { setProblem(e.message); } finally { setRunning(false); }
  };

  const submit = async (note) => {
    setRunning(true); setProblem(null);
    try {
      await assistant.submit(session.id, note);
      await refresh(session.id);
    } catch (e) { setProblem(e.message); } finally { setRunning(false); }
  };

  const turns = session?.turns || [];
  const active = session?.template || template; // once started, the session's template is the one in use
  const labels = session?.stages ? Object.entries(session.stages) : STEPS;
  const titleOf = (id) => (options || []).find((o) => o.id === id)?.title || id;
  const askedBefore = (i) => [...turns.slice(0, i)].reverse().find((t) => t.role === 'user')?.text;
  const suggestions = template?.suggestions?.length ? template.suggestions : null;
  const empty = !turns.length && !pending;
  const lastProposal = turns.map((t) => t.kind).lastIndexOf('proposal');
  const forClient = (page.params || []).some((p) => p.name === 'clientId');
  const off = enabled === false;

  const composer = (
    <Composer value={text} onChange={setText} onSend={() => send()} running={running} disabled={off} autoFocus
      placeholder={off ? 'The assistant is not configured' : empty ? 'Describe what you want…' : 'Reply, or ask for a change…'} />
  );

  return (
    <div className={'chat' + (empty ? ' is-empty' : '')}>
      <header className="chat-head">
        <button className="btn ghost small" onClick={onClose} title="Back to the dashboard"><Icon name="left" size={14} /> {page.title}</button>
        <div className="chat-head-title"><Icon name="spark" size={14} /> Assistant</div>
        {active && active.id !== CUSTOM_WIDGET && (
          <span className="tag tpl-tag" title={'Built from the ' + active.title + ' template'}>
            <Icon name={active.icon} size={11} /> {active.title} · {KIND_LABELS[active.kind] || active.kind}
          </span>
        )}
        <div className="spacer" />
        {toolbar}
        <button className="btn small" onClick={reset} disabled={running || empty} title="Start a new conversation"><Icon name="plus" size={12} /> New chat</button>
      </header>

      <div className="chat-scroll" ref={scroller}>
        <div className="chat-col">
          {off && <div className="error-box">The assistant isn't configured: set ANTHROPIC_API_KEY for the backend.</div>}
          {empty ? (
            <div className="chat-hero">
              <div className="chat-hero-mark"><Icon name="spark" size={22} stroke={2} /></div>
              <h1>{template && template.id !== CUSTOM_WIDGET ? template.title : `What should we add to ${page.title}?`}</h1>
              <p>{template && template.id !== CUSTOM_WIDGET
                ? 'Describe what you need and I’ll build it the way this template defines, then show you a live preview.'
                : 'Describe a widget in plain words, or start from a template.'} Nothing is added until you approve it, and what you approve goes to your personal library.</p>
              <TemplatePicker options={options} template={template || options?.find((o) => o.id === CUSTOM_WIDGET)} inputs={inputs}
                onTemplate={(t) => { setTemplate(t); setInputs({}); }} onInputs={setInputs} disabled={off || running} />
              {composer}
              <div className="chat-suggest">
                {(suggestions || SUGGESTIONS[forClient ? 'client' : 'book']).map((s) => (
                  <button key={s} className="chat-chip" disabled={off || running} onClick={() => send(s)}>{s}</button>
                ))}
              </div>
            </div>
          ) : (
            <div className="chat-thread">
              {turns.map((t, i) => t.role === 'user' ? (
                <div key={i} className="msg user"><div className="msg-bubble">{t.text}</div></div>
              ) : (
                <div key={i} className={'msg assistant ' + (t.kind || '')}>
                  <Avatar />
                  <div className="msg-main">
                    <div className="msg-text">{t.text}</div>
                    {t.kind === 'redirect' && t.templateId && (
                      <div className="pv-actions">
                        <button className="btn primary small" disabled={running} onClick={() => switchTo(t.templateId, askedBefore(i))}>
                          Switch to {titleOf(t.templateId)} <Icon name="right" size={12} /></button>
                        <span className="pv-hint">starts a new conversation with the same request</span>
                      </div>
                    )}
                    {i === lastProposal && session.proposal && (
                      <Proposal proposal={session.proposal} reviewState={session.reviewState} placed={session.status === 'placed'}
                        submission={session.submission} busy={running} page={page} onAdd={add} onVerify={verify} onView={onClose} onSubmit={submit} />
                    )}
                  </div>
                </div>
              ))}
              {pending && <div className="msg user"><div className="msg-bubble">{pending}</div></div>}
              {running && pending && <Thinking stages={stages} activity={activity} ack={ack} labels={labels} />}
              {problem && (
                <div className="msg assistant error"><Avatar /><div className="msg-main"><div className="msg-text">{problem}</div></div></div>
              )}
              {session && turns.length > 0 && !running && (
                <div className="chat-details">
                  <button className="pv-why" onClick={() => setShowDetails((v) => !v)}>
                    {showDetails ? 'Hide run details' : 'Run details'}{session.costUsd ? ` · $${session.costUsd.toFixed(3)}` : ''}
                  </button>
                  {showDetails && <Details key={turns.length} sessionId={session.id} />}
                </div>
              )}
            </div>
          )}
        </div>
      </div>

      {!empty && (
        <div className="chat-dock">
          <div className="chat-col">
            {composer}
            <div className="chat-foot">Proposals use the selected {forClient ? 'client' : 'data'} as a sample. Review figures before adding.</div>
          </div>
        </div>
      )}
    </div>
  );
}
