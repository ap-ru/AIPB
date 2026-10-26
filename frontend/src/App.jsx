import { useState } from 'react';
import { NavLink, Navigate, Route, Routes } from 'react-router-dom';
import { usePlatform } from './lib/platform.jsx';
import DynamicPage from './pages/DynamicPage.jsx';
import Client360Page from './pages/Client360Page.jsx';
import Icon from './components/Icon.jsx';

/** Demo role switcher: no sign-in yet, so whoever is at the screen picks who the app acts as. */
function RolePicker() {
  const { role, setRole, roles, can } = usePlatform();
  if (!roles) return null;
  return (
    <div className="role-picker" title={can.description}>
      <span className="role-initial" aria-hidden="true">{(can.label || role).charAt(0)}</span>
      <label className="nav-label">
        <span className="label">Viewing as</span>
        <select value={role} onChange={(e) => setRole(e.target.value)}>
          {roles.map((r) => <option key={r.id} value={r.id}>{r.label}</option>)}
        </select>
      </label>
      <div className="role-desc nav-label">{can.description}</div>
    </div>
  );
}

function Sidebar() {
  const { layout, error, source, assistantOpen } = usePlatform();
  const [logoOk, setLogoOk] = useState(true);
  return (
    <aside className="sidebar">
      <div className="brand">
        <span className="brand-mark brand-rail" aria-hidden="true">C</span>
        {logoOk ? (
          <img className="brand-logo" src="/citizens_logo.svg" alt="Citizens" onError={() => setLogoOk(false)} />
        ) : (
          <span className="brand-fallback">Citizens</span>
        )}
        <small>AI Powered Banker · Commercial</small>
      </div>
      <nav className="nav">
        <div className="nav-section">Commercial bank</div>
        {error && <div className="error-box">API unreachable</div>}
        {(layout ? layout.pages : []).map((p) => (
          <NavLink key={p.id} to={'/page/' + p.id} title={assistantOpen ? p.title : undefined}><Icon name={p.icon} /><span className="nav-label">{p.title}</span></NavLink>
        ))}
      </nav>
      <div className="sidebar-foot">
        <RolePicker />
        {layout && !error
          ? <div className="row" title="Connected"><span className="dot" /> <span className="nav-label">CONNECTED</span></div>
          : <div className="row"><span className="dot" style={{ background: 'var(--red)' }} /> <span className="nav-label">{error ? 'API UNREACHABLE' : 'CONNECTING…'}</span></div>}
        {source && (
          <div className="row" style={{ marginTop: 6 }}
            title={source.connected
              ? `Pages: MongoDB, read directly · Assistant: ${source.mcp?.connected ? `${source.mcp.server} · ${source.mcp.url}` : 'MCP server unreachable'}`
              : source.error}>
            <span className="dot" style={source.connected ? undefined : { background: 'var(--red)' }} />
            <span className="nav-label">{source.connected ? `SOURCE · ${source.database} · ${source.collections.length} collections` : 'SOURCE UNREACHABLE'}</span>
          </div>
        )}
      </div>
    </aside>
  );
}

export default function App() {
  const { assistantOpen } = usePlatform();
  return (
    <div className={'shell' + (assistantOpen ? ' rail' : '')}>
      <Sidebar />
      <main className="main">
        <Routes>
          <Route path="/" element={<Navigate to="/page/command-center" replace />} />
          <Route path="/page/:pageId" element={<DynamicPage />} />
          <Route path="/clients/:clientId" element={<Client360Page />} />
          <Route path="*" element={<Navigate to="/" replace />} />
        </Routes>
      </main>
    </div>
  );
}
