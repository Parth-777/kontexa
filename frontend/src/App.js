import { Navigate, Route, Routes } from 'react-router-dom';
import HomePage from './pages/HomePage';
import SignInPage from './pages/SignInPage';
import AcceptInvitePage from './pages/AcceptInvitePage';
import ActivateInvitePage from './pages/ActivateInvitePage';
import ResetPasswordPage from './pages/ResetPasswordPage';
import TenantCataloguePage from './pages/TenantCataloguePage';
import TenantDashboardPage from './pages/TenantDashboardPage';
import UserDashboardPage from './pages/UserDashboardPage';
import { getSession, isAdminRole } from './api/session';
import './App.css';

function RequireAuth({ children, adminOnly = false }) {
  const session = getSession();
  if (!session) return <Navigate to="/signin" replace />;
  if (adminOnly && !isAdminRole(session.role)) {
    return <Navigate to="/user/dashboard" replace />;
  }
  return children;
}

function App() {
  return (
    <Routes>
      <Route path="/" element={<HomePage />} />
      <Route path="/signin" element={<SignInPage />} />
      <Route path="/activate-invite" element={<ActivateInvitePage />} />
      <Route path="/accept-invite" element={<AcceptInvitePage />} />
      <Route path="/reset-password" element={<ResetPasswordPage />} />
      <Route
        path="/tenant/dashboard"
        element={
          <RequireAuth adminOnly>
            <TenantDashboardPage />
          </RequireAuth>
        }
      />
      <Route
        path="/tenant/catalogue/:catalogueId"
        element={
          <RequireAuth adminOnly>
            <TenantCataloguePage />
          </RequireAuth>
        }
      />
      <Route
        path="/user/dashboard"
        element={
          <RequireAuth>
            <UserDashboardPage />
          </RequireAuth>
        }
      />
      <Route path="/dashboard" element={<Navigate to="/tenant/dashboard" replace />} />
      <Route path="*" element={<Navigate to="/" replace />} />
    </Routes>
  );
}

export default App;
