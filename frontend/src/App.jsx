import { useState } from 'react';
import { NavLink, Navigate, Route, Routes } from 'react-router-dom';
import { usePlatform } from './lib/platform.jsx';
import DynamicPage from './pages/DynamicPage.jsx';
import Client360Page from './pages/Client360Page.jsx';
import Icon from './components/Icon.jsx';

/** Builder / Approver: who is acting in the maker-checker flow (a demo stand-in for sign-in). */
function RoleToggle() {
  const { role, setRole } = usePlatform();
  return (
    <div className="role-toggle" role="radiogroup" aria-label="Acting as">
      {[['builder', 'Builder', 'B'], ['approver', 'Approver', 'A']].map(([k, label, short]) => (
        <button key={k} role="radio" aria-checked={role === k} className={role === k ? 'active' : ''} onClick={() => setRole(k)}
          title={k === 'builder' ? 'Builder: your widgets go for approval' : 'Approver: approve widgets; yours are approved as you save them'}>
          <span className="nav-label">{label}</span><span className="role-short" aria-hidden="true">{short}</span>
        </button>
      ))}
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
        <div className="label nav-label" style={{ marginBottom: 6 }}>Acting as</div>
        <RoleToggle />
        {layout && !error
          ? <div className="row" title="Connected"><span className="dot" /> <span className="nav-label">CONNECTED</span></div>
          : <div className="row"><span className="dot" style={{ background: 'var(--red)' }} /> <span className="nav-label">{error ? 'API UNREACHABLE' : 'CONNECTING…'}</span></div>}
        {source && (
          <div className="row" style={{ marginTop: 6 }} title={source.connected ? `${source.server} · ${source.mcpUrl}` : source.error}>
            <span className="dot" style={source.connected ? undefined : { background: 'var(--red)' }} />
            <span className="nav-label">{source.connected ? `SOURCE · MCP · ${source.database} · ${source.collections.length} collections` : 'SOURCE UNREACHABLE'}</span>
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
