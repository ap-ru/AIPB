import { useNavigate, useParams } from 'react-router-dom';
import { useApi } from '../lib/useApi.js';
import { Loading, ErrorBox } from '../components/common.jsx';
import { ClientProfile } from '../widgets/client360.jsx';
import TopBar from '../components/TopBar.jsx';
import Icon from '../components/Icon.jsx';

export default function Client360Page() {
  const { clientId } = useParams();
  const q = useApi('/api/clients/' + clientId);
  const nav = useNavigate();

  if (q.loading && !q.data) return <Loading label="Loading Client 360" />;
  if (q.error) return <ErrorBox error={q.error} />;
  const c = q.data.client;

  return (
    <>
      <TopBar title="Client 360" sub={`${c.name} · ${c.segment}`}
        left={<button className="btn ghost small" onClick={() => nav(-1)}><Icon name="left" size={13} /></button>} />
      <div className="content">
        <ClientProfile data={q.data} />
      </div>
    </>
  );
}
