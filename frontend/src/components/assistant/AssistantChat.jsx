import { useEffect, useLayoutEffect, useRef, useState } from 'react';
import { assistant, skills } from '../../lib/assistant.js';
import { usePlatform, LAYOUT_ID } from '../../lib/platform.jsx';
import SpecView from '../../widgets/spec/SpecView.jsx';
import SkillsPanel from '../skills/SkillsPanel.jsx';
import Icon from '../Icon.jsx';

const STEPS = [['intake', 'Understanding'], ['build', 'Finding the data'], ['review', 'Checking the numbers'], ['present', 'Designing']];

const SUGGESTIONS = {
  client: ['Deposits by product for this client', 'Loan exposure vs. committed limits', 'Monthly balance trend over the last year', 'Open opportunities by stage'],
  book: ['Top 10 clients by total deposits', 'Pipeline by stage', 'Clients with maturing loans this quarter', 'Deposit mix across the book'],
};

function Avatar() {
  return <div className="msg-avatar" aria-hidden="true"><Icon name="spark" size={14} stroke={2} /></div>;
}

/** Live progress while a stage pipeline runs: the current step, the steps so far, and the latest activity. */
function Thinking({ stages, activity, ack }) {
  const seen = new Set(stages.map((s) => s.stage));
  const steps = STEPS.filter(([k]) => k !== 'review' || seen.has('review'));
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

/**
 * Where the conversation's widget stands after Add & share: personal (only its Builder sees it on the page) while it
 * waits for review or is being revised; live for everyone once approved; off the page if rejected. A change to a
 * global widget is not on any page until it is approved.
 */
function Approval({ session, proposal, onView }) {
  const change = Boolean(proposal.modifies) || session.modifies;
  const view = <button className="btn small" onClick={onView}>View on page <Icon name="right" size={12} /></button>;
  if (session.approval === 'approved') {
    return <div className="pv-actions"><span className="tag green">Approved · v{session.approvedVersion} · live for everyone</span>{view}</div>;
  }
  if (session.approval === 'rejected') {
    return <div className="pv-actions"><span className="tag red">Rejected</span>
      <span className="pv-hint">{change ? 'The current version stays as it is.' : 'Removed from the page; it stays in your personal library.'}</span></div>;
  }
  if (session.approval === 'changes_requested') {
    return <div className="pv-actions"><span className="tag pink">Feedback &amp; revise</span>
      <span className="pv-hint">Reply with the changes, then Add &amp; share again.</span></div>;
  }
  return (
    <div className="pv-actions">
      <span className="tag amber">Pending review</span>
      <span className="pv-hint">{change ? 'Everyone, you included, keeps seeing the current version until it’s approved.'
        : 'In your personal library: only you see it on the page until a Reviewer approves it.'}</span>
      {!change && view}
    </div>
  );
}

/** A proposal, previewed in the same card chrome a placed widget gets, with Add & share (and a note) under it. */
function Proposal({ proposal, reviewState, placed, submitted, session, reviewer, busy, page, onAdd, onVerify, onView }) {
  const [confirm, setConfirm] = useState(false);
  const [open, setOpen] = useState(false);
  const [note, setNote] = useState('');
  const failed = reviewState === 'failed' && proposal.kind === 'new';
  const p = proposal.preview || {};
  const reuse = proposal.kind === 'reuse';
  const again = ['pending', 'changes_requested'].includes(session.approval);
  const label = reuse ? <><Icon name="plus" size={12} /> Add to {page.title}</>
    : reviewer ? <><Icon name="plus" size={12} /> Approve &amp; add</>
    : <><Icon name="plus" size={12} /> {again ? 'Update & share again' : proposal.modifies ? 'Share the change' : 'Add & share'}</>;
  const title = reuse ? 'Already approved: adds it to the page'
    : reviewer ? 'Approves it into the global library; live for everyone'
    : proposal.modifies ? 'Sends the change for review; the current version stays live until it is approved'
    : 'Saves it to your personal library and the page (only you see it there) and shares it with the Reviewers';
  return (
    <div className="pv">
      {proposal.modifies && <div className="pv-hint">Change to “{session.modifiesTitle || proposal.modifies}” · from v{proposal.baseVersion}</div>}
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
      {reuse && placed ? (
        <div className="pv-actions">
          <span className="pv-placed"><Icon name="flag" size={13} /> Added to {page.title}</span>
          <button className="btn small" onClick={onView}>View on page <Icon name="right" size={12} /></button>
        </div>
      ) : submitted ? (
        <Approval session={session} proposal={proposal} onView={onView} />
      ) : (
        <>
          {failed && (
            <label className="pv-confirm">
              <input type="checkbox" checked={confirm} onChange={(e) => setConfirm(e.target.checked)} />
              I understand the review could not verify these numbers.
            </label>
          )}
          {!reuse && !reviewer && (
            <textarea className="pv-note" rows={2} value={note} onChange={(e) => setNote(e.target.value)}
              placeholder="Note for the Reviewer (optional): what should they know?" />
          )}
          <div className="pv-actions">
            <button className="btn primary small" disabled={busy || (failed && !confirm)} onClick={() => onAdd(confirm, note)} title={title}>
              {label}</button>
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

/** When modifying a widget: the conversations that made its earlier versions, read-only and collapsed. */
function Earlier({ earlier }) {
  const [open, setOpen] = useState(false);
  if (!earlier?.length) return null;
  const count = earlier.reduce((n, e) => n + (e.turns?.length || 0), 0);
  return (
    <div className="chat-earlier">
      <button className="pv-why" onClick={() => setOpen((o) => !o)} aria-expanded={open}>
        {open ? 'Hide' : 'Show'} earlier conversation{earlier.length > 1 ? 's' : ''} · {count} message{count === 1 ? '' : 's'}
        <Icon name={open ? 'up' : 'down'} size={11} />
      </button>
      {open && earlier.map((e, i) => (
        <div key={e.sessionId + i} className="chat-earlier-group">
          <div className="label">{e.title}{e.version ? ` · became v${e.version}` : ' · not approved'}</div>
          {(e.turns || []).map((t, j) => (
            <div key={j} className={'skill-turn ' + t.role}>
              <b>{t.role === 'user' ? 'Builder' : t.role === 'reviewer' ? 'Reviewer' : 'Assistant'}:</b> {t.text}
            </div>
          ))}
        </div>
      ))}
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
 * The widget assistant as a full-page conversation. It proposes; the banker approves before anything is added.
 * Kept mounted while hidden, so going back to the dashboard and returning keeps the conversation.
 */
export default function AssistantChat({ page, pageParams, toolbar, onClose, openSessionId, onElsewhere }) {
  const { setLayout, role, can } = usePlatform();
  const [showSkills, setShowSkills] = useState(false);
  const [attention, setAttention] = useState(0);
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
  const scroller = useRef(null);

  useEffect(() => { assistant.status().then((s) => setEnabled(s.enabled)).catch(() => setEnabled(false)); }, []);
  const reset = () => { setSession(null); setStages([]); setActivity([]); setAck(null); setProblem(null); setPending(null); setShowDetails(false); };
  useEffect(reset, [page.id]);
  const loadAttention = () => skills.list().then((r) => setAttention(r.attention)).catch(() => {});
  useEffect(() => { loadAttention(); }, [role]); // eslint-disable-line react-hooks/exhaustive-deps

  // a conversation opened from Skills (to revise a sent-back widget, or to modify one), on this page or another
  const show = async (s) => {
    if (s.pageId !== page.id && onElsewhere) { onElsewhere(s.pageId, s.id, s.params); return; }
    reset();
    setSession(s);
  };
  const openSession = async (id) => {
    setShowSkills(false);
    try { await show(await assistant.session(id)); } catch (e) { setProblem(e.message); }
  };
  const modify = async (widget) => {
    setShowSkills(false);
    try { await show(await assistant.create(LAYOUT_ID, page.id, pageParams, widget.id)); } catch (e) { setProblem(e.message); }
  };
  useEffect(() => { if (openSessionId) openSession(openSessionId); }, [openSessionId]); // eslint-disable-line react-hooks/exhaustive-deps
  // decisions a Reviewer takes elsewhere show up in an open conversation
  useEffect(() => {
    if (!session?.submissionId || running) return undefined;
    const t = setInterval(() => assistant.session(session.id).then((s) => {
      if (s.approval !== session.approval || s.turns.length !== session.turns.length) setSession(s);
    }).catch(() => {}), 15000);
    return () => clearInterval(t);
  }, [session, running]);
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

  const run = async (start) => {
    setRunning(true); setProblem(null); setStages([]); setActivity([]); setAck(null);
    try {
      let s = session;
      if (!s) { s = await assistant.create(LAYOUT_ID, page.id, pageParams); setSession(s); }
      await start(s.id);
      await refresh(s.id);
    } catch (e) {
      setProblem(e.message);
    } finally { setRunning(false); setPending(null); setAck(null); }
  };

  const send = (raw = text) => {
    const t = raw.trim();
    if (!t || running) return;
    setText('');
    setPending(t);
    run((id) => assistant.message(id, t, pageParams, onEvent));
  };

  const verify = () => { setPending('Verify the numbers.'); run((id) => assistant.verify(id, onEvent)); };

  const add = async (confirmUnverified, note) => {
    setRunning(true); setProblem(null);
    try {
      const r = await assistant.approve(session.id, confirmUnverified, note);
      if (r.layout) setLayout(r.layout);
      await refresh(session.id);
      loadAttention();
    } catch (e) { setProblem(e.message); } finally { setRunning(false); }
  };

  const turns = session?.turns || [];
  const empty = !turns.length && !pending;
  const lastProposal = turns.map((t) => t.kind).lastIndexOf('proposal');
  const submitted = lastProposal >= 0 && turns.slice(lastProposal + 1).some((t) => ['submitted', 'approved'].includes(t.kind));
  const forClient = (page.params || []).some((p) => p.name === 'clientId');
  const off = enabled === false;

  const composer = (
    <Composer value={text} onChange={setText} onSend={() => send()} running={running} disabled={off} autoFocus
      placeholder={off ? 'The assistant is not configured' : empty ? 'Describe the widget you want…' : 'Reply, or ask for a change…'} />
  );

  return (
    <div className={'chat' + (empty ? ' is-empty' : '')}>
      <header className="chat-head">
        <button className="btn ghost small" onClick={onClose} title="Back to the dashboard"><Icon name="left" size={14} /> {page.title}</button>
        <div className="chat-head-title"><Icon name="spark" size={14} /> Assistant</div>
        <div className="spacer" />
        {toolbar}
        <button className="btn small skills-btn" onClick={() => setShowSkills(true)}
          title={can.canReview ? 'Widgets waiting for approval, approved and stock widgets' : 'Your widgets in progress, approved and stock widgets'}>
          <Icon name="skill" size={12} /> Skills{attention > 0 && <span className="count-badge" aria-label={attention + ' need attention'}>{attention}</span>}
        </button>
        <button className="btn small" onClick={reset} disabled={running || empty} title="Start a new conversation"><Icon name="plus" size={12} /> New chat</button>
      </header>
      {showSkills && (
        <SkillsPanel page={page} pageParams={pageParams} onClose={() => { setShowSkills(false); loadAttention(); if (session) refresh(session.id).catch(() => {}); }}
          onOpenSession={(id) => openSession(id)} onModify={modify} onChanged={loadAttention} />
      )}

      <div className="chat-scroll" ref={scroller}>
        <div className="chat-col">
          {off && <div className="error-box">The assistant isn't configured: set ANTHROPIC_API_KEY for the backend.</div>}
          {empty ? (
            <div className="chat-hero">
              <div className="chat-hero-mark"><Icon name="spark" size={22} stroke={2} /></div>
              <h1>What should we add to {page.title}?</h1>
              <p>Describe a widget in plain words. I’ll find the data, check it, and show you a live preview. {can.canReview
                ? 'What you approve goes live for everyone.'
                : 'Add & share puts it in your personal library and on the page - only you see it there until a Reviewer approves it.'}</p>
              {composer}
              <div className="chat-suggest">
                {SUGGESTIONS[forClient ? 'client' : 'book'].map((s) => (
                  <button key={s} className="chat-chip" disabled={off || running} onClick={() => send(s)}>{s}</button>
                ))}
              </div>
            </div>
          ) : (
            <div className="chat-thread">
              <Earlier key={session?.id} earlier={session?.earlier} />
              {turns.map((t, i) => t.role === 'user' ? (
                <div key={i} className="msg user"><div className="msg-bubble">{t.text}</div></div>
              ) : t.role === 'reviewer' ? (
                <div key={i} className={'msg reviewer ' + (t.kind || '')}>
                  <div className="msg-avatar reviewer" aria-hidden="true"><Icon name="flag" size={14} stroke={2} /></div>
                  <div className="msg-main"><div className="msg-who">Reviewer</div><div className="msg-text">{t.text}</div></div>
                </div>
              ) : (
                <div key={i} className={'msg assistant ' + (t.kind || '')}>
                  <Avatar />
                  <div className="msg-main">
                    <div className="msg-text">{t.text}</div>
                    {i === lastProposal && session.proposal && (
                      <Proposal proposal={session.proposal} reviewState={session.reviewState} placed={session.status === 'placed'}
                        submitted={submitted} session={session} reviewer={can.canReview}
                        busy={running} page={page} onAdd={add} onVerify={verify} onView={onClose} />
                    )}
                  </div>
                </div>
              ))}
              {pending && <div className="msg user"><div className="msg-bubble">{pending}</div></div>}
              {running && pending && <Thinking stages={stages} activity={activity} ack={ack} />}
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
