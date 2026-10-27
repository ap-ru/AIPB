import TopBar from '../components/TopBar.jsx';

/** CM&A workspace · Signals: a placeholder page, nothing on it yet. */
export default function SignalsPage() {
  return (
    <>
      <TopBar title="Signals" sub="CM&A workspace" />
      <div className="content">
        <div className="empty soft">Nothing here yet.</div>
      </div>
    </>
  );
}
