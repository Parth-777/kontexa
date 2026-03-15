import { useState } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { tenantLogin, userLogin } from '../api/authApi';
import './SignInPage.css';

function SignInPage() {
  const navigate = useNavigate();
  const [userId, setUserId] = useState('');
  const [password, setPassword] = useState('');
  const [accountType, setAccountType] = useState('tenant');
  const [isSubmitting, setIsSubmitting] = useState(false);
  const [errorMessage, setErrorMessage] = useState('');

  const handleSubmit = async (event) => {
    event.preventDefault();
    const normalizedUserId = userId.trim();
    const normalizedPassword = password.trim();
    if (!normalizedUserId || !normalizedPassword) {
      setErrorMessage('User ID and password are required.');
      return;
    }

    try {
      setIsSubmitting(true);
      setErrorMessage('');
      if (accountType === 'tenant') {
        const loginResult = await tenantLogin(normalizedUserId, normalizedPassword);
        window.sessionStorage.setItem(
          'kontexaTenantSession',
          JSON.stringify({
            userId: loginResult.userId || normalizedUserId,
            tenantId: loginResult.tenantId || '',
            tenantSchema: loginResult.tenantSchema || '',
            cloudDbLink: loginResult.cloudDbLink || '',
            loggedInAt: new Date().toISOString(),
          })
        );
        navigate('/tenant/dashboard');
      } else {
        const loginResult = await userLogin(normalizedUserId, normalizedPassword);
        window.sessionStorage.setItem(
          'kontexaUserSession',
          JSON.stringify({
            userId: loginResult.userId || normalizedUserId,
            tenantId: loginResult.tenantId || '',
            tenantSchema: loginResult.tenantSchema || '',
            position: loginResult.position || '',
            loggedInAt: new Date().toISOString(),
          })
        );
        navigate('/user/dashboard');
      }
    } catch (error) {
      setErrorMessage(error.message || 'Unable to login');
    } finally {
      setIsSubmitting(false);
    }
  };

  return (
    <div className="signin-page">
      <main className="signin-main">
        <section className="signin-card">
          <h1>{accountType === 'tenant' ? 'Tenant sign in' : 'User sign in'}</h1>
          <div className="signin-toggle">
            <button
              className={accountType === 'tenant' ? 'active' : ''}
              onClick={() => setAccountType('tenant')}
              type="button"
            >
              Tenant
            </button>
            <button
              className={accountType === 'user' ? 'active' : ''}
              onClick={() => setAccountType('user')}
              type="button"
            >
              User
            </button>
          </div>

          <form onSubmit={handleSubmit}>
            <label htmlFor="userid">User ID</label>
            <input
              id="userid"
              type="text"
              value={userId}
              onChange={(event) => setUserId(event.target.value)}
              required
            />
            <label htmlFor="password">Password</label>
            <input
              id="password"
              type="password"
              value={password}
              onChange={(event) => setPassword(event.target.value)}
              required
            />
            <button type="submit" disabled={isSubmitting}>
              {isSubmitting ? 'Signing in...' : 'Sign In'}
            </button>
          </form>
          {errorMessage ? <p className="signin-error">{errorMessage}</p> : null}
          <p className="signin-back-link">
            <Link to="/">Back to home</Link>
          </p>
        </section>
      </main>
    </div>
  );
}

export default SignInPage;
