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
    title: 'Active signals by category',
    description: client ? 'Unresolved signals for the selected client, counted by category.' : 'Unresolved signals across the book, counted by category.',
    params: client ? [{ name: 'clientId', type: 'string', description: 'Client key, e.g. CL-0001' }] : [],
    data: {
      collection: 'signals',
      pipeline: [
        { $match: client ? { clientId: { $param: 'clientId' }, stage: { $ne: 'Resolved' } } : { stage: { $ne: 'Resolved' } } },
        { $group: { _id: '$category', signals: { $sum: 1 } } },
        { $sort: { signals: -1 } },
        { $project: { _id: 0, category: '$_id', signals: 1 } },
      ],
      columns: [
        { name: 'category', type: 'string', label: 'Category' },
        { name: 'signals', type: 'number', unit: 'count', format: 'integer', label: 'Active signals' },
      ],
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
  const { role } = usePlatform();

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
      // maker-checker: an approver's save is approved at once and placed; a builder's waits for approval
      if (saved.status === 'approved') await onPlace(saved);
      else setSent(`Sent “${saved.title}” for approval${update ? ` as a change to v${saved.baseVersion}` : ''}. It can be placed once an approver approves it; follow it in the assistant's Skills window.`);
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
          A widget is a spec: a read-only pipeline over one source collection, its columns, and one primitive to show them.
          {pageParams && Object.keys(pageParams).length > 0 && <> Sample values from this page: <span className="mono">{JSON.stringify(pageParams)}</span>.</>}
        </div>
        <textarea className="mono spec-editor" spellCheck={false} value={text} onChange={(e) => { setText(e.target.value); setReport(null); }} />
        <div className="row">
          <button className="btn" onClick={() => setText(template(page))} disabled={busy}>Reset</button>
          <div className="spacer" style={{ flex: 1 }} />
          <button className="btn" onClick={validate} disabled={busy}>{busy ? 'Working…' : 'Validate'}</button>
          {role === 'approver'
            ? exists
              ? <button className="btn primary" onClick={() => save(true)} disabled={busy}>Approve as new version & place</button>
              : <button className="btn primary" onClick={() => save(false)} disabled={busy || (report && !report.ok)}>Approve to library & place</button>
            : exists
              ? <button className="btn primary" onClick={() => save(true)} disabled={busy}>Submit as a change for approval</button>
              : <button className="btn primary" onClick={() => save(false)} disabled={busy || (report && !report.ok)}>Submit for approval</button>}
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
                        truncated={report.preview.truncated} total={report.preview.total} />
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
