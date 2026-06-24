import { useState } from 'react';
import { Link, useSearchParams } from 'react-router-dom';
import { confirmPasswordReset, requestPasswordReset } from '../api/authApi';
import './SignInPage.css';

function ResetPasswordPage() {
  const [params] = useSearchParams();
  const token = params.get('token');

  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [message, setMessage] = useState('');
  const [error, setError] = useState('');
  const [submitting, setSubmitting] = useState(false);

  const handleRequest = async (e) => {
    e.preventDefault();
    try {
      setSubmitting(true);
      setError('');
      const result = await requestPasswordReset(email.trim());
      setMessage(result.message || 'If the account exists, a reset link has been generated.');
      if (result.resetLink) setMessage(`${result.message} Dev link: ${result.resetLink}`);
    } catch (err) {
      setError(err.message || 'Request failed');
    } finally {
      setSubmitting(false);
    }
  };

  const handleConfirm = async (e) => {
    e.preventDefault();
    try {
      setSubmitting(true);
      setError('');
      await confirmPasswordReset({ token, password: password.trim() });
      setMessage('Password updated. You can sign in now.');
    } catch (err) {
      setError(err.message || 'Reset failed');
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <div className="auth-shell auth-shell--centered">
      <div className="auth-right auth-right--solo">
        <div className="auth-panel">
          <div className="auth-panel-header">
            <h2 className="auth-panel-title">
              {token ? 'Set new password' : 'Reset password'}
            </h2>
          </div>

          {token ? (
            <form className="auth-form" onSubmit={handleConfirm}>
              <div className="auth-field">
                <label className="auth-label">New password</label>
                <input
                  className="auth-input"
                  type="password"
                  value={password}
                  onChange={(e) => setPassword(e.target.value)}
                  required
                />
              </div>
              {error && <div className="auth-error">{error}</div>}
              {message && <p className="feedback-msg feedback-success">{message}</p>}
              <button type="submit" className="auth-submit" disabled={submitting}>
                {submitting ? 'Updating…' : 'Update password'}
              </button>
            </form>
          ) : (
            <form className="auth-form" onSubmit={handleRequest}>
              <div className="auth-field">
                <label className="auth-label">Email</label>
                <input
                  className="auth-input"
                  type="email"
                  value={email}
                  onChange={(e) => setEmail(e.target.value)}
                  required
                />
              </div>
              {error && <div className="auth-error">{error}</div>}
              {message && <p className="feedback-msg feedback-success">{message}</p>}
              <button type="submit" className="auth-submit" disabled={submitting}>
                {submitting ? 'Sending…' : 'Send reset link'}
              </button>
            </form>
          )}

          <p className="auth-back"><Link to="/signin">← Back to sign in</Link></p>
        </div>
      </div>
    </div>
  );
}

export default ResetPasswordPage;
