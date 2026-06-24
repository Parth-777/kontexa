import { useEffect, useMemo, useState } from 'react';
import { Link, useNavigate, useSearchParams } from 'react-router-dom';
import { acceptInvite, validateInviteToken } from '../api/authApi';
import './SignInPage.css';

function formatExpiry(iso) {
  if (!iso) return '';
  const d = new Date(iso);
  if (Number.isNaN(d.getTime())) return iso;
  return d.toLocaleString(undefined, {
    month: 'short',
    day: 'numeric',
    year: 'numeric',
    hour: 'numeric',
    minute: '2-digit',
  });
}

function ActivateInvitePage() {
  const navigate = useNavigate();
  const [params] = useSearchParams();
  const token = params.get('token') || '';

  const [inviteInfo, setInviteInfo] = useState(null);
  const [displayName, setDisplayName] = useState('');
  const [password, setPassword] = useState('');
  const [confirmPassword, setConfirmPassword] = useState('');
  const [error, setError] = useState('');
  const [loading, setLoading] = useState(true);
  const [submitting, setSubmitting] = useState(false);
  const [success, setSuccess] = useState(false);

  useEffect(() => {
    if (!token) {
      setError('Activation link is invalid or incomplete.');
      setLoading(false);
      return;
    }
    validateInviteToken(token)
      .then((info) => {
        setInviteInfo(info);
        setDisplayName(info.displayName || info.email || '');
      })
      .catch((err) => setError(err.message || 'This invitation is invalid or has expired.'))
      .finally(() => setLoading(false));
  }, [token]);

  const expiryLabel = useMemo(
    () => formatExpiry(inviteInfo?.expiresAt),
    [inviteInfo?.expiresAt]
  );

  const handleSubmit = async (e) => {
    e.preventDefault();
    if (!password.trim()) {
      setError('Password is required.');
      return;
    }
    if (password !== confirmPassword) {
      setError('Passwords do not match.');
      return;
    }
    try {
      setSubmitting(true);
      setError('');
      await acceptInvite({ token, password: password.trim(), displayName: displayName.trim() });
      setSuccess(true);
      setTimeout(() => navigate('/signin'), 2400);
    } catch (err) {
      setError(err.message || 'Failed to activate account');
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <div className="auth-shell auth-shell--centered">
      <div className="auth-right auth-right--solo">
        <div className="auth-panel auth-panel--invite">
          <div className="auth-panel-header">
            <div className="auth-brand auth-brand--compact">
              <span className="auth-logo-mark">K</span>
              <span className="auth-wordmark">KONTEXA</span>
            </div>
            <h2 className="auth-panel-title">Activate your account</h2>
            <p className="auth-panel-sub">
              Complete your profile to join your team workspace.
            </p>
          </div>

          {loading && (
            <div className="auth-state-block">
              <p className="muted-text">Validating invitation…</p>
            </div>
          )}

          {!loading && inviteInfo && !success && (
            <form className="auth-form" onSubmit={handleSubmit}>
              <div className="invite-context-card">
                <p className="invite-context-line">
                  Workspace <strong>{inviteInfo.workspaceName}</strong>
                </p>
                {inviteInfo.inviterName && (
                  <p className="invite-context-sub">
                    Invited by {inviteInfo.inviterName}
                  </p>
                )}
                <p className="invite-context-sub">
                  Role: <strong>{inviteInfo.role}</strong>
                </p>
                {expiryLabel && (
                  <p className="invite-expiry-notice">
                    This invitation expires on {expiryLabel}.
                  </p>
                )}
              </div>

              <div className="auth-field">
                <label className="auth-label">Work email</label>
                <input className="auth-input" value={inviteInfo.email} readOnly />
              </div>
              <div className="auth-field">
                <label className="auth-label">Full name</label>
                <input
                  className="auth-input"
                  value={displayName}
                  onChange={(e) => setDisplayName(e.target.value)}
                  placeholder="Your name"
                  required
                />
              </div>
              <div className="auth-field">
                <label className="auth-label">Password</label>
                <input
                  className="auth-input"
                  type="password"
                  value={password}
                  onChange={(e) => setPassword(e.target.value)}
                  placeholder="Create a secure password"
                  required
                  minLength={8}
                />
              </div>
              <div className="auth-field">
                <label className="auth-label">Confirm password</label>
                <input
                  className="auth-input"
                  type="password"
                  value={confirmPassword}
                  onChange={(e) => setConfirmPassword(e.target.value)}
                  placeholder="Re-enter password"
                  required
                  minLength={8}
                />
              </div>
              {error && <div className="auth-error">{error}</div>}
              <button type="submit" className="auth-submit" disabled={submitting}>
                {submitting ? 'Activating account…' : 'Activate account'}
              </button>
            </form>
          )}

          {success && (
            <div className="auth-state-block auth-state-block--success">
              <p className="auth-success-title">Account activated</p>
              <p className="muted-text">Redirecting you to sign in…</p>
            </div>
          )}

          {!loading && !inviteInfo && !success && error && (
            <div className="auth-state-block">
              <div className="auth-error">{error}</div>
              <p className="muted-text invite-help-text">
                Ask your workspace admin to send a new invitation.
              </p>
            </div>
          )}

          <p className="auth-back"><Link to="/signin">← Back to sign in</Link></p>
        </div>
      </div>
    </div>
  );
}

export default ActivateInvitePage;
