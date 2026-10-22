import { useEffect, useState } from 'react';
import { usePlatform } from '../lib/platform.jsx';

export default function TopBar({ title, sub, actions, badge, left }) {
  const { layout } = usePlatform();
  const [now, setNow] = useState(new Date());
  useEffect(() => {
    const t = setInterval(() => setNow(new Date()), 30000);
    return () => clearInterval(t);
  }, []);
  const stamp = now.toLocaleDateString('en-US', { month: 'short', day: 'numeric' }).toUpperCase() + ' · ' +
    now.toLocaleTimeString('en-US', { hour: '2-digit', minute: '2-digit', hour12: false });
  return (
    <div className="topbar">
      {left}
      <div>
        <div className="row">
          <h2>{title}</h2>
          {badge && <span className="tag violet">⚡ {badge}</span>}
        </div>
        {sub && <div className="sub">{sub}</div>}
      </div>
      <div className="spacer" />
      {actions}
      <div className="snapshot">
        <span className="dot" />
        LAYOUT v{layout?.version ?? '-'} · SNAPSHOT {stamp}
      </div>
    </div>
  );
}
