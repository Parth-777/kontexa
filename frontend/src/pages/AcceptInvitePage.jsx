import { Navigate, useSearchParams } from 'react-router-dom';

/** Legacy route — redirects to /activate-invite */
function AcceptInvitePage() {
  const [params] = useSearchParams();
  const token = params.get('token');
  const target = token
    ? `/activate-invite?token=${encodeURIComponent(token)}`
    : '/activate-invite';
  return <Navigate to={target} replace />;
}

export default AcceptInvitePage;
