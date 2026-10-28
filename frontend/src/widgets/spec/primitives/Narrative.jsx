import { TONE_COLOR } from '../theme.js';

/**
 * An insight: the sentences the backend rendered from the first row (NarrativeRenderer), the first as the headline,
 * each marked by its tone, with the key figures beside them. Nothing is computed here, so every view reads the same.
 */
export default function Narrative({ narrative }) {
  const lines = narrative?.lines || [];
  if (!lines.length) return <div className="empty">Nothing to say for this selection</div>;
  const [lead, ...rest] = lines;
  const figures = narrative.figures || [];
  return (
    <div className="spec-narrative">
      {figures.length > 0 && (
        <div className="spec-narrative-figures">
          {figures.map((f) => (
            <div key={f.name} className="spec-narrative-figure">
              <div className="spec-narrative-value">{f.text}</div>
              <div className="spec-narrative-label">{f.label}</div>
            </div>
          ))}
        </div>
      )}
      <p className="spec-narrative-lead">{lead.text}</p>
      {rest.length > 0 && (
        <ul className="spec-narrative-lines">
          {rest.map((l, i) => (
            <li key={i}>
              <span className="spec-list-dot" style={{ background: TONE_COLOR[l.tone] ?? 'var(--line-2)' }} aria-hidden="true" />
              <span>{l.text}</span>
            </li>
          ))}
        </ul>
      )}
    </div>
  );
}
