import { useEffect, useMemo, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { usePlatform } from '../lib/platform.jsx';
import { skills, KIND_LABELS, fits, pageScope } from '../lib/skills.js';
import Icon from '../components/Icon.jsx';
import { Loading } from '../components/common.jsx';
import TopBar from '../components/TopBar.jsx';

const SCOPE_LABELS = { client: 'Client pages', book: 'Book-wide pages' };

/** The template's form: its inputs, and the page the skill is for (only pages it fits). Starting opens the assistant there. */
function StartModal({ template, pages, onClose }) {
  const nav = useNavigate();
  const fitting = pages.filter((p) => fits(template, p));
  const [pageId, setPageId] = useState(fitting[0]?.id || '');
  const [inputs, setInputs] = useState({});
  const missing = template.inputs.filter((i) => i.required && !inputs[i.name]);
  const start = () => nav('/page/' + pageId, { state: { skill: { templateId: template.id, inputs, at: Date.now() } } });
  return (
    <div className="overlay" onClick={onClose}>
      <div className="modal studio-modal" onClick={(e) => e.stopPropagation()}>
        <div className="modal-head">
          <span className="studio-icon"><Icon name={template.icon} size={16} /></span>
          <h3 style={{ margin: 0, fontSize: 15 }}>{template.title}</h3>
          <span className="tag">{KIND_LABELS[template.kind] || template.kind}</span>
          <button className="icon-btn" style={{ marginLeft: 'auto' }} onClick={onClose} aria-label="Close"><Icon name="x" /></button>
        </div>
        <div className="modal-body studio-form">
          <p className="muted" style={{ margin: 0 }}>{template.summary}</p>
          <label className="field">
            <span className="label">Page</span>
            {fitting.length
              ? <select value={pageId} onChange={(e) => setPageId(e.target.value)}>
                  {fitting.map((p) => <option key={p.id} value={p.id}>{p.title}</option>)}
                </select>
              : <div className="error-box">No page fits this template. It needs a {template.pageScopes.map((s) => SCOPE_LABELS[s].toLowerCase()).join(' or ')}.</div>}
          </label>
          {template.inputs.map((i) => (
            <label key={i.name} className="field">
              <span className="label">{i.label}{i.required ? ' *' : ''}</span>
              {i.type === 'enum'
                ? <select value={inputs[i.name] || ''} onChange={(e) => setInputs({ ...inputs, [i.name]: e.target.value })}>
                    <option value="">{i.required ? 'Choose…' : 'Let the assistant decide'}</option>
                    {i.options.map((o) => <option key={o} value={o}>{o}</option>)}
                  </select>
                : <input type={i.type === 'number' ? 'number' : 'text'} value={inputs[i.name] || ''}
                    onChange={(e) => setInputs({ ...inputs, [i.name]: e.target.value })} />}
              {i.help && <small className="dim">{i.help}</small>}
            </label>
          ))}
          <div className="row" style={{ gap: 8, justifyContent: 'flex-end' }}>
            <button className="btn" onClick={onClose}>Cancel</button>
            <button className="btn primary" disabled={!pageId || missing.length > 0} onClick={start}>
              <Icon name="spark" size={13} /> Start with the assistant</button>
          </div>
        </div>
      </div>
    </div>
  );
}

function TemplateCard({ template, onPick }) {
  return (
    <button className="card studio-card" onClick={() => onPick(template)}>
      <div className="studio-card-head">
        <span className="studio-icon"><Icon name={template.icon} size={16} /></span>
        <span className="studio-card-title">{template.title}</span>
        <span className="tag">{KIND_LABELS[template.kind] || template.kind}</span>
      </div>
      <p className="studio-card-summary">{template.summary}</p>
      <div className="studio-card-foot">
        <span>{template.pageScopes.map((s) => SCOPE_LABELS[s]).join(' · ')}</span>
        {template.primitives.length > 0 && <span>{template.primitives.join(', ')}</span>}
      </div>
    </button>
  );
}

/**
 * The Skills Studio (Builders and Reviewers): templates for well-made skills. Picking one asks for its inputs and a
 * page, then opens the assistant on that page with the template guiding the run.
 */
export default function SkillsStudio() {
  const { layout, can, tick } = usePlatform();
  const [templates, setTemplates] = useState(null);
  const [error, setError] = useState(null);
  const [kind, setKind] = useState('all');
  const [picked, setPicked] = useState(null);

  useEffect(() => {
    if (!can.canUseAssistant) return;
    setError(null);
    skills.templates().then(setTemplates).catch(setError);
  }, [can.canUseAssistant, tick]);

  const kinds = useMemo(() => [...new Set((templates || []).map((t) => t.kind))], [templates]);
  const groups = useMemo(() => {
    const out = new Map();
    (templates || []).filter((t) => kind === 'all' || t.kind === kind).forEach((t) => {
      if (!out.has(t.category)) out.set(t.category, []);
      out.get(t.category).push(t);
    });
    return [...out.entries()];
  }, [templates, kind]);

  if (!can.canUseAssistant) {
    return (
      <>
        <TopBar title="Skills Studio" />
        <div className="content"><div className="empty">The Skills Studio is for Builders and Reviewers. Switch roles under “Viewing as”.</div></div>
      </>
    );
  }
  const pages = layout?.pages || [];
  const counts = pages.reduce((m, p) => ({ ...m, [pageScope(p)]: (m[pageScope(p)] || 0) + 1 }), {});
  return (
    <>
      <TopBar title="Skills Studio" sub="Start from a template and the assistant builds a skill of that shape, checked against the template’s rules."
        actions={kinds.length > 1 && (
          <div className="row" style={{ gap: 6 }}>
            {['all', ...kinds].map((k) => (
              <button key={k} className={'chip' + (kind === k ? ' active' : '')} onClick={() => setKind(k)}>
                {k === 'all' ? 'All' : (KIND_LABELS[k] || k) + 's'}</button>
            ))}
          </div>
        )} />
      <div className="content">
        {error && <div className="error-box">Could not load the templates: {error.message}</div>}
        {!templates && !error && <Loading label="Loading templates" />}
        {groups.map(([category, list]) => (
          <section key={category} className="studio-group">
            <div className="label">{category}</div>
            <div className="studio-grid">
              {list.map((t) => <TemplateCard key={t.id} template={t} onPick={setPicked} />)}
            </div>
          </section>
        ))}
        {templates && (
          <div className="dim" style={{ fontSize: 12, marginTop: 8 }}>
            {pages.length} pages: {counts.client || 0} client, {counts.book || 0} book-wide. Templates are offered on the pages they fit.
          </div>
        )}
      </div>
      {picked && <StartModal template={picked} pages={pages} onClose={() => setPicked(null)} />}
    </>
  );
}
