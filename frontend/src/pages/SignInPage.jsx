import { useState } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { workspaceLogin, mcpComplete } from '../api/authApi';
import { isAdminRole, saveSession } from '../api/session';
import './SignInPage.css';

function SignInPage() {
  const navigate = useNavigate();
  const [workspaceId,   setWorkspaceId]   = useState('');
  const [password,      setPassword]      = useState('');
  const [isSubmitting,  setIsSubmitting]  = useState(false);
  const [errorMessage,  setErrorMessage]  = useState('');
  const [showPassword,  setShowPassword]  = useState(false);

  const handleSubmit = async (event) => {
    event.preventDefault();
    const normalizedWorkspaceId = workspaceId.trim();
    const normalizedPassword = password.trim();
    if (!normalizedWorkspaceId || !normalizedPassword) {
      setErrorMessage('Workspace ID and password are required.');
      return;
    }
    try {
      setIsSubmitting(true);
      setErrorMessage('');

      const loginResult = await workspaceLogin({
        workspaceId: normalizedWorkspaceId,
        password: normalizedPassword,
        deviceLabel: 'Kontexa Web',
      });

      saveSession({
        userId:         loginResult.userId || normalizedWorkspaceId,
        email:          loginResult.email || '',
        displayName:    loginResult.displayName || normalizedWorkspaceId,
        role:           loginResult.role || 'VIEWER',
        workspaceId:    loginResult.workspaceId || '',
        workspaceSlug:  loginResult.workspaceSlug || loginResult.tenantId || '',
        tenantId:       loginResult.tenantId || loginResult.workspaceSlug || '',
        tenantSchema:   loginResult.tenantSchema || loginResult.workspaceSlug || '',
        cloudDbLink:    loginResult.cloudDbLink || '',
        accessToken:    loginResult.accessToken || '',
        refreshToken:   loginResult.refreshToken || '',
        authSource:     loginResult.authSource || '',
        loggedInAt:     new Date().toISOString(),
      });

      // MCP mode: instead of navigating to a dashboard, hand the session off to
      // the local MCP via a single-use code, then redirect to its loopback.
      const mcpParams = new URLSearchParams(window.location.search);
      if (mcpParams.get('mcp') === '1') {
        const redirectUri = mcpParams.get('redirect_uri');
        const state = mcpParams.get('state');
        if (!redirectUri || !state) {
          setErrorMessage('Invalid MCP login request (missing redirect_uri or state).');
          return;
        }
        const handoff = await mcpComplete({
          redirectUri,
          state,
          accessToken: loginResult.accessToken,
        });
        window.location.assign(handoff.redirectUri);
        return;
      }

      if (isAdminRole(loginResult.role)) {
        navigate('/tenant/dashboard');
      } else {
        navigate('/user/dashboard');
      }
    } catch (error) {
      setErrorMessage(error.message || 'Invalid credentials. Please try again.');
    } finally {
      setIsSubmitting(false);
    }
  };

  return (
    <div className="auth-shell">

      <div className="auth-left">
        <div className="auth-left-inner">
          <div className="auth-brand">
            <span className="auth-logo-mark">K</span>
            <span className="auth-wordmark">KONTEXA</span>
          </div>

          <div className="auth-hero-copy">
            <p className="auth-category">Business Intelligence Platform</p>
            <h1 className="auth-headline">
              Enterprise AI reasoning<br />for modern data teams.
            </h1>
            <p className="auth-subheadline">
              Turn warehouse data into structured analytical intelligence.
              Ask questions. Get findings. Make decisions.
            </p>
          </div>

          <div className="auth-trust">
            <div className="trust-item">
              <span className="trust-icon">◈</span>
              <span>Workspace-scoped access</span>
            </div>
            <div className="trust-item">
              <span className="trust-icon">▤</span>
              <span>Multi-warehouse connectors</span>
            </div>
            <div className="trust-item">
              <span className="trust-icon">◉</span>
              <span>Role-based access control</span>
            </div>
          </div>
        </div>

        <div className="auth-left-orb auth-left-orb--1" />
        <div className="auth-left-orb auth-left-orb--2" />
        <div className="auth-left-grid" />
      </div>

      <div className="auth-right">
        <div className="auth-panel">

          <div className="auth-panel-brand">
            <span className="auth-logo-mark auth-logo-mark--sm">K</span>
            <span className="auth-wordmark auth-wordmark--sm">KONTEXA</span>
          </div>

          <div className="auth-panel-header">
            <h2 className="auth-panel-title">Sign in to Kontexa</h2>
            <p className="auth-panel-sub">
              Access your enterprise analytics workspace.
            </p>
          </div>

          <form className="auth-form" onSubmit={handleSubmit} noValidate>
            <div className="auth-field">
              <label className="auth-label" htmlFor="workspace">Workspace ID</label>
              <input
                id="workspace"
                className="auth-input"
                type="text"
                value={workspaceId}
                onChange={(e) => setWorkspaceId(e.target.value)}
                placeholder="your workspace id"
                autoComplete="username"
                required
              />
            </div>

            <div className="auth-field">
              <label className="auth-label" htmlFor="password">Password</label>
              <div className="auth-input-wrap">
                <input
                  id="password"
                  className="auth-input"
                  type={showPassword ? 'text' : 'password'}
                  value={password}
                  onChange={(e) => setPassword(e.target.value)}
                  placeholder="••••••••••••"
                  autoComplete="current-password"
                  required
                />
                <button
                  type="button"
                  className="auth-show-pw"
                  onClick={() => setShowPassword((v) => !v)}
                  tabIndex={-1}
                >
                  {showPassword ? 'Hide' : 'Show'}
                </button>
              </div>
            </div>

            {errorMessage && (
              <div className="auth-error">
                <span className="auth-error-icon">!</span>
                {errorMessage}
              </div>
            )}

            <button type="submit" className="auth-submit" disabled={isSubmitting}>
              {isSubmitting ? <span className="auth-spinner" /> : 'Sign in'}
            </button>
          </form>

          <p className="auth-back">
            <Link to="/reset-password">Forgot password?</Link>
            <span className="auth-back-sep"> · </span>
            <Link to="/">← Back to home</Link>
          </p>

          <p className="auth-sso-note">SSO (Okta, Azure AD) — coming soon</p>
        </div>
      </div>
    </div>
  );
}

export default SignInPage;
