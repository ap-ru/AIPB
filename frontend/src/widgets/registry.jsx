import { ClientTable, KpiStrip } from './book.jsx';
import { Client360Widget } from './client360.jsx';
import SpecWidget from './spec/SpecWidget.jsx';

/**
 * Widget registry - maps the `type` stored in the MongoDB layout document to a React component.
 * `spec` renders a library widget definition (declarative spec); the others are the legacy built-in types, which must
 * also be declared in the backend WidgetManifest.
 */
export const REGISTRY = {
  'kpi-strip': { component: KpiStrip, bare: true, label: 'KPI strip' },
  'client-table': { component: ClientTable, label: 'Client book' },
  'client-360': { component: Client360Widget, label: 'Client 360 relationship view' },
  spec: { component: SpecWidget, label: 'Library widget', library: true },
};

export const DEFAULT_SPAN = { 'kpi-strip': 12, 'client-table': 12, 'client-360': 12 };
