import { useCallback, useEffect, useMemo, useState } from 'react';
import { submitOnboardingLead } from '../api/onboardingApi';

const COMPANY_SIZES = [
  '1–50',
  '51–200',
  '201–1,000',
  '1,001–5,000',
  '5,000+',
];

const WAREHOUSES = [
  'BigQuery',
  'Snowflake',
  'Redshift',
  'Databricks',
  'Other',
];

const EMAIL_RE = /^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}$/;

function GetAccessModal({ isOpen, onClose, sourcePage = 'homepage' }) {
  const [fullName, setFullName] = useState('');
  const [workEmail, setWorkEmail] = useState('');
  const [companyName, setCompanyName] = useState('');
  const [companySize, setCompanySize] = useState('');
  const [dataWarehouse, setDataWarehouse] = useState('');
  const [useCase, setUseCase] = useState('');
  const [showOptional, setShowOptional] = useState(false);
  const [submitting, setSubmitting] = useState(false);
  const [success, setSuccess] = useState(false);
  const [submitError, setSubmitError] = useState('');
  const [touched, setTouched] = useState({});

  const errors = useMemo(() => {
    const next = {};
    if (touched.fullName && !fullName.trim()) {
      next.fullName = 'Full name is required';
    }
    if (touched.workEmail) {
      if (!workEmail.trim()) next.workEmail = 'Work email is required';
      else if (!EMAIL_RE.test(workEmail.trim())) next.workEmail = 'Enter a valid email address';
    }
    if (touched.companyName && !companyName.trim()) {
      next.companyName = 'Company name is required';
    }
    return next;
  }, [fullName, workEmail, companyName, touched]);

  const isValid = fullName.trim() && EMAIL_RE.test(workEmail.trim()) && companyName.trim();

  const resetForm = useCallback(() => {
    setFullName('');
    setWorkEmail('');
    setCompanyName('');
    setCompanySize('');
    setDataWarehouse('');
    setUseCase('');
    setShowOptional(false);
    setSubmitting(false);
    setSuccess(false);
    setSubmitError('');
    setTouched({});
  }, []);

  const handleClose = useCallback(() => {
    if (submitting) return;
    resetForm();
    onClose();
  }, [onClose, resetForm, submitting]);

  useEffect(() => {
    if (!isOpen) return undefined;

    const onKeyDown = (e) => {
      if (e.key === 'Escape') handleClose();
    };

    document.addEventListener('keydown', onKeyDown);
    document.body.style.overflow = 'hidden';

    return () => {
      document.removeEventListener('keydown', onKeyDown);
      document.body.style.overflow = '';
    };
  }, [isOpen, handleClose]);

  const handleSubmit = async (e) => {
    e.preventDefault();
    setTouched({ fullName: true, workEmail: true, companyName: true });
    if (!isValid) return;

    try {
      setSubmitting(true);
      setSubmitError('');
      await submitOnboardingLead({
        fullName: fullName.trim(),
        workEmail: workEmail.trim(),
        companyName: companyName.trim(),
        companySize: companySize || undefined,
        dataWarehouse: dataWarehouse || undefined,
        useCase: useCase.trim() || undefined,
        sourcePage,
      });
      setSuccess(true);
    } catch (err) {
      setSubmitError(err.message || 'Something went wrong. Please try again.');
    } finally {
      setSubmitting(false);
    }
  };

  if (!isOpen) return null;

  return (
    <div
      className="hp-modal-overlay"
      role="presentation"
      onClick={handleClose}
    >
      <div
        className="hp-modal"
        role="dialog"
        aria-modal="true"
        aria-labelledby="hp-modal-title"
        onClick={(e) => e.stopPropagation()}
      >
        <button type="button" className="hp-modal-close" onClick={handleClose} aria-label="Close">
          ×
        </button>

        {success ? (
          <div className="hp-modal-success">
            <div className="hp-modal-success-icon">✓</div>
            <h2 id="hp-modal-title" className="hp-modal-title">Request received</h2>
            <p className="hp-modal-success-text">
              Thanks. Our team will contact you shortly to provision your Kontexa workspace.
            </p>
            <p className="hp-modal-footer-note">
              Enterprise onboarding typically takes less than 24 hours.
            </p>
            <button type="button" className="hp-btn hp-btn--primary hp-modal-done" onClick={handleClose}>
              Done
            </button>
          </div>
        ) : (
          <>
            <div className="hp-modal-header">
              <p className="hp-modal-eyebrow">Enterprise access</p>
              <h2 id="hp-modal-title" className="hp-modal-title">Get access to Kontexa</h2>
              <p className="hp-modal-sub">
                Request a workspace for your team. We&apos;ll provision access and reach out within one business day.
              </p>
            </div>

            <form className="hp-modal-form" onSubmit={handleSubmit} noValidate>
              <div className="hp-modal-field">
                <label className="hp-modal-label" htmlFor="ga-fullname">Full name</label>
                <input
                  id="ga-fullname"
                  className={`hp-modal-input ${errors.fullName ? 'hp-modal-input--error' : ''}`}
                  value={fullName}
                  onChange={(e) => setFullName(e.target.value)}
                  onBlur={() => setTouched((t) => ({ ...t, fullName: true }))}
                  placeholder="Alex Morgan"
                  autoComplete="name"
                />
                {errors.fullName && <span className="hp-modal-error">{errors.fullName}</span>}
              </div>

              <div className="hp-modal-field">
                <label className="hp-modal-label" htmlFor="ga-email">Work email</label>
                <input
                  id="ga-email"
                  type="email"
                  className={`hp-modal-input ${errors.workEmail ? 'hp-modal-input--error' : ''}`}
                  value={workEmail}
                  onChange={(e) => setWorkEmail(e.target.value)}
                  onBlur={() => setTouched((t) => ({ ...t, workEmail: true }))}
                  placeholder="you@company.com"
                  autoComplete="email"
                />
                {errors.workEmail && <span className="hp-modal-error">{errors.workEmail}</span>}
              </div>

              <div className="hp-modal-field">
                <label className="hp-modal-label" htmlFor="ga-company">Company name</label>
                <input
                  id="ga-company"
                  className={`hp-modal-input ${errors.companyName ? 'hp-modal-input--error' : ''}`}
                  value={companyName}
                  onChange={(e) => setCompanyName(e.target.value)}
                  onBlur={() => setTouched((t) => ({ ...t, companyName: true }))}
                  placeholder="Acme Corp"
                  autoComplete="organization"
                />
                {errors.companyName && <span className="hp-modal-error">{errors.companyName}</span>}
              </div>

              <button
                type="button"
                className="hp-modal-expand"
                onClick={() => setShowOptional((v) => !v)}
                aria-expanded={showOptional}
              >
                {showOptional ? 'Hide optional details' : 'Add optional details'}
                <span className="hp-modal-expand-chevron">{showOptional ? '▴' : '▾'}</span>
              </button>

              {showOptional && (
                <div className="hp-modal-optional">
                  <div className="hp-modal-field">
                    <label className="hp-modal-label" htmlFor="ga-size">Company size</label>
                    <select
                      id="ga-size"
                      className="hp-modal-input hp-modal-select"
                      value={companySize}
                      onChange={(e) => setCompanySize(e.target.value)}
                    >
                      <option value="">Select size</option>
                      {COMPANY_SIZES.map((s) => (
                        <option key={s} value={s}>{s}</option>
                      ))}
                    </select>
                  </div>

                  <div className="hp-modal-field">
                    <label className="hp-modal-label" htmlFor="ga-warehouse">Data warehouse</label>
                    <select
                      id="ga-warehouse"
                      className="hp-modal-input hp-modal-select"
                      value={dataWarehouse}
                      onChange={(e) => setDataWarehouse(e.target.value)}
                    >
                      <option value="">Select warehouse</option>
                      {WAREHOUSES.map((w) => (
                        <option key={w} value={w}>{w}</option>
                      ))}
                    </select>
                  </div>

                  <div className="hp-modal-field">
                    <label className="hp-modal-label" htmlFor="ga-usecase">Use case</label>
                    <textarea
                      id="ga-usecase"
                      className="hp-modal-input hp-modal-textarea"
                      value={useCase}
                      onChange={(e) => setUseCase(e.target.value)}
                      placeholder="What analytical questions do you need answered?"
                      rows={3}
                    />
                  </div>
                </div>
              )}

              {submitError && <div className="hp-modal-submit-error">{submitError}</div>}

              <button
                type="submit"
                className="hp-btn hp-btn--primary hp-modal-submit"
                disabled={!isValid || submitting}
              >
                {submitting ? <span className="hp-modal-spinner" /> : 'Request access'}
              </button>

              <p className="hp-modal-footer-note">
                Enterprise onboarding typically takes less than 24 hours.
              </p>
            </form>
          </>
        )}
      </div>
    </div>
  );
}

export default GetAccessModal;
