/**
 * Chart theme for spec widgets. Series colors are a fixed categorical order - assigned by series position, never
 * cycled - validated for color-vision deficiency and >= 3:1 contrast on the white card (brand green first).
 * MUTED is the de-emphasis step of the brand ramp: with an `emphasis` intent, the emphasized mark keeps SERIES[0]
 * and the rest recede to MUTED. Status tones map to the app's tag classes, which always carry their text label, so
 * state is never color alone.
 */
export const SERIES = ['#008555', '#2a78d6', '#eb6834', '#6250d6'];
export const MAX_SERIES = SERIES.length;
/** Parts of a whole (composition): the series order extended to 6, validated the same way; the rest fold into Other. */
export const PARTS = [...SERIES, '#c98500', '#d6629a'];
export const OTHER = '#c9c7c0';
export const MUTED = '#b9dccd';

export const GRID = '#efefe8';
export const AXIS_TEXT = '#76716f';
export const INK = '#231f20';
export const SURFACE = '#ffffff';

export const TONE_CLASS = { good: 'green', warn: 'amber', bad: 'red', info: 'blue', neutral: '' };
export const TONE_COLOR = { good: '#0f8a3c', warn: '#b86e00', bad: '#c8102e', info: '#1b5e8c', neutral: '#8a8687' };

/** Charts draw in with a short animation unless the reader asked for reduced motion. */
export const ANIMATE = !(typeof window !== 'undefined' && window.matchMedia?.('(prefers-reduced-motion: reduce)').matches);

export const TICK = { fill: AXIS_TEXT, fontSize: 11, fontFamily: 'var(--display)' };

/** Index of the row an emphasis intent points at, or -1. */
export function emphasisIndex(emphasis, rows, key) {
  if (!emphasis || emphasis === 'none' || !rows.length) return -1;
  if (emphasis === 'latest') return rows.length - 1;
  let best = -1;
  rows.forEach((r, i) => {
    const v = r[key];
    if (typeof v !== 'number') return;
    if (best < 0 || (emphasis === 'max' ? v > rows[best][key] : v < rows[best][key])) best = i;
  });
  return best;
}

/** Tone of a change: good / bad by polarity; neutral polarity (or no change) stays neutral. */
export function deltaTone(delta, polarity) {
  if (!delta || polarity === 'neutral') return 'neutral';
  const up = delta > 0;
  return (polarity === 'lowerIsBetter' ? !up : up) ? 'good' : 'bad';
}
