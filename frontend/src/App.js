import { Navigate, Route, Routes } from 'react-router-dom';
import HomePage from './pages/HomePage';
import SignInPage from './pages/SignInPage';
import TenantDashboardPage from './pages/TenantDashboardPage';
import UserDashboardPage from './pages/UserDashboardPage';
import './App.css';

function isTenantAuthenticated() {
  const session = window.sessionStorage.getItem('kontexaTenantSession');
  return Boolean(session);
}

function isUserAuthenticated() {
  const session = window.sessionStorage.getItem('kontexaUserSession');
  return Boolean(session);
}

function RequireTenantAuth({ children }) {
  if (!isTenantAuthenticated()) return <Navigate to="/signin" replace />;
  return children;
}

function RequireUserAuth({ children }) {
  if (!isUserAuthenticated()) return <Navigate to="/signin" replace />;
  return children;
}

function App() {
  return (
    <Routes>
      <Route path="/" element={<HomePage />} />
      <Route path="/signin" element={<SignInPage />} />
      <Route
        path="/tenant/dashboard"
        element={
          <RequireTenantAuth>
            <TenantDashboardPage />
          </RequireTenantAuth>
        }
      />
      <Route
        path="/user/dashboard"
        element={
          <RequireUserAuth>
            <UserDashboardPage />
          </RequireUserAuth>
        }
      />
      <Route path="/dashboard" element={<Navigate to="/tenant/dashboard" replace />} />
      <Route path="*" element={<Navigate to="/" replace />} />
    </Routes>
  );
}

export default App;