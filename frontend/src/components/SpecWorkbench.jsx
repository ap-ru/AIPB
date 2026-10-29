import { useState } from 'react';
import { api } from '../lib/api.js';
import { LAYOUT_ID, usePlatform } from '../lib/platform.jsx';
import SpecView from '../widgets/spec/SpecView.jsx';
import Icon from './Icon.jsx';

/** A starting point that fits the page: client-scoped where the page picks a client, book-wide otherwise. */
function template(page) {
  const client = page?.params?.some((p) => p.name === 'clientId');
  return JSON.stringify({
    id: client ? 'client-signals-by-category' : 'book-signals-by-category',
    specVersion: 2,
    title: 'Active signals by category',
    description: client ? 'Unresolved signals for the selected client, counted by category.' : 'Unresolved signals across the book, counted by category.',
    params: client ? [{ name: 'clientId', type: 'string', description: 'Client key, e.g. CL-0001' }] : [],
    data: {
      custom: {
        cypher: (client ? 'MATCH (s:Signal)-[:ABOUT]->(:Client {clientId: $clientId})\n' : 'MATCH (s:Signal)\n')
          + "WHERE s.stage <> 'Resolved'\nRETURN s.category AS category, count(*) AS signals\nORDER BY signals DESC",
        columns: [
          { name: 'category', type: 'string', label: 'Category' },
          { name: 'signals', type: 'number', unit: 'count', format: 'integer', label: 'Active signals' },
        ],
      },
    },
    view: { primitive: 'bar', x: 'category', y: ['signals'], orientation: 'horizontal' },
    layout: { span: 6 },
  }, null, 2);
}

function Issues({ title, items, tone }) {
  if (!items?.length) return null;
  return (
    <div className="stack" style={{ gap: 6 }}>
      <div className="label">{title} ({items.length})</div>
      {items.map((i, k) => (
        <div key={k} className={'spec-issue ' + tone}>
          <span className="mono">{i.code}</span>{i.path && <span className="muted"> · {i.path}</span>}
          <div>{i.message}</div>
        </div>
      ))}
    </div>
  );
}

/**
 * The manual authoring loop the assistant will automate: edit a spec, validate it against the real data (errors,
 * warnings and a live preview drawn by the same primitives), then save it to the library and place it on this page.
 */
export default function SpecWorkbench({ page, pageParams, onPlace }) {
  const [text, setText] = useState(() => template(page));
  const [report, setReport] = useState(null);
  const [parsed, setParsed] = useState(null);
  const [problem, setProblem] = useState(null);
  const [busy, setBusy] = useState(false);
  const [exists, setExists] = useState(false);
  const [sent, setSent] = useState(null);
  const { can } = usePlatform();

  const parse = () => {
    try {
      const spec = JSON.parse(text);
      setParsed(spec);
      return spec;
    } catch (e) {
      setProblem('Not valid JSON: ' + e.message);
      return null;
    }
  };
  const body = (spec) => ({ spec, layoutId: LAYOUT_ID, pageId: page.id, params: pageParams });

  const validate = async () => {
    setProblem(null); setExists(false);
    const spec = parse();
    if (!spec) return;
    setBusy(true);
    try { setReport(await api.post('/api/widgets/validate', body(spec))); }
    catch (e) { setProblem(e.message); }
    finally { setBusy(false); }
  };

  const save = async (update) => {
    setProblem(null); setSent(null);
    const spec = parse();
    if (!spec) return;
    setBusy(true);
    try {
      const saved = update ? await api.put('/api/widgets/library/' + encodeURIComponent(spec.id), body(spec))
        : await api.post('/api/widgets/library', body(spec));
      // maker-checker: a Builder's new widget goes to their personal library and is placed where only they see it;
      // a Reviewer's is approved at once and placed for everyone; a change to a global widget waits for approval
      if (saved.status === 'approved' || (saved.status === 'personal' && !update)) await onPlace(saved);
      else if (saved.status === 'personal') setSent(`Updated “${saved.title}” in your personal library. Submit it for review from the Library tab or Skills.`);
      else setSent(`Sent the change to “${saved.title}” for approval as v${(saved.baseVersion || 0) + 1}. Everyone keeps seeing the current version until it's approved.`);
    } catch (e) {
      if (e.status === 422 && e.body?.report) setReport(e.body.report);
      else if (e.status === 409) setExists(true);
      setProblem(e.message);
    } finally { setBusy(false); }
  };

  return (
    <div className="spec-workbench">
      <div className="stack" style={{ gap: 8 }}>
        <div className="muted" style={{ fontSize: 12 }}>
          A widget is a spec: a semantic query, or a read-only Cypher query over the bank graph (values as $parameters, today as datetime($asOfDate)), its columns, and one primitive to show them.
          {pageParams && Object.keys(pageParams).length > 0 && <> Sample values from this page: <span className="mono">{JSON.stringify(pageParams)}</span>.</>}
        </div>
        <textarea className="mono spec-editor" spellCheck={false} value={text} onChange={(e) => { setText(e.target.value); setReport(null); }} />
        <div className="row">
          <button className="btn" onClick={() => setText(template(page))} disabled={busy}>Reset</button>
          <div className="spacer" style={{ flex: 1 }} />
          <button className="btn" onClick={validate} disabled={busy}>{busy ? 'Working…' : 'Validate'}</button>
          {can.canReview
            ? exists
              ? <button className="btn primary" onClick={() => save(true)} disabled={busy}>Approve as new version & place</button>
              : <button className="btn primary" onClick={() => save(false)} disabled={busy || (report && !report.ok)}>Approve to library & place</button>
            : exists
              ? <button className="btn primary" onClick={() => save(true)} disabled={busy}>Save as a new version</button>
              : <button className="btn primary" onClick={() => save(false)} disabled={busy || (report && !report.ok)}>Save to my library &amp; place</button>}
        </div>
      </div>
      <div className="stack" style={{ gap: 12, minWidth: 0 }}>
        {sent && <div className="pv-placed"><Icon name="flag" size={13} /> {sent}</div>}
        {problem && <div className="error-box">⚠ {problem}</div>}
        {!report && !problem && <div className="empty">Validate to check the spec against the real data and see a preview.</div>}
        {report && (
          <>
            <div className="row">
              <span className={'tag ' + (report.ok ? 'green' : 'red')}>{report.ok ? 'Valid' : 'Invalid'}</span>
              {report.sampleParams && Object.keys(report.sampleParams).length > 0 &&
                <span className="muted" style={{ fontSize: 12 }}>ran with {JSON.stringify(report.sampleParams)}</span>}
            </div>
            <Issues title="Errors" items={report.errors} tone="bad" />
            <Issues title="Warnings" items={report.warnings} tone="warn" />
            {report.preview && parsed?.view && (
              <div className="card">
                <div className="card-head"><h3>{parsed.title}</h3>
                  <span className="muted" style={{ marginLeft: 'auto', fontSize: 11 }}>{report.preview.count} rows</span></div>
                <div className="card-body">
                  {report.preview.rows.length
                    ? <SpecView view={parsed.view} columns={report.preview.columns} rows={report.preview.rows}
                        truncated={report.preview.truncated} total={report.preview.total} narrative={report.preview.narrative} />
                    : <div className="empty">No rows</div>}
                </div>
              </div>
            )}
          </>
        )}
      </div>
    </div>
  );
}
