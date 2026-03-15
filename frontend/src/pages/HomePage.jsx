import { useEffect, useMemo, useState } from 'react';
import { Link } from 'react-router-dom';

function HomePage() {
  const sampleQuestions = useMemo(
    () => [
      'Show top 10 shows by watch time in 2024',
      'List all movies from India released in 2020',
      'Compare active users by tenant for last month',
      'Which categories grew fastest quarter over quarter?',
    ],
    []
  );
  const [questionIndex, setQuestionIndex] = useState(0);
  const [typedText, setTypedText] = useState('');
  const [isDeleting, setIsDeleting] = useState(false);

  useEffect(() => {
    const currentQuestion = sampleQuestions[questionIndex];
    const doneTyping = !isDeleting && typedText === currentQuestion;
    const doneDeleting = isDeleting && typedText === '';
    const delay = doneTyping ? 1300 : 45;

    const timer = window.setTimeout(() => {
      if (doneTyping) {
        setIsDeleting(true);
        return;
      }
      if (doneDeleting) {
        setIsDeleting(false);
        setQuestionIndex((prev) => (prev + 1) % sampleQuestions.length);
        return;
      }
      const nextLength = typedText.length + (isDeleting ? -1 : 1);
      setTypedText(currentQuestion.slice(0, nextLength));
    }, delay);

    return () => window.clearTimeout(timer);
  }, [typedText, isDeleting, questionIndex, sampleQuestions]);

  return (
    <div className="home-page">
      <div className="home-grid" />
      <div className="home-orb home-orb-left" />
      <div className="home-orb home-orb-right" />

      <nav className="home-nav">
        <Link to="/" className="brand">
          KONTEXA
        </Link>
        <Link to="/signin" className="nav-link">
          Sign in
        </Link>
      </nav>
      <section className="hero">
        <h1>Ask your data in plain English.</h1>
        <p>
          Kontexa helps teams query complex datasets, generate charts, and build insights with
          tenant-aware access and schema-safe SQL.
        </p>
        <div className="hero-query-box">
          <span>{typedText}</span>
          <i className="cursor" />
        </div>
        <div className="hero-actions">
          <Link to="/signin" className="hero-btn primary">
            Start now
          </Link>
          <a href="#how-it-works" className="hero-btn secondary">
            See how it works
          </a>
        </div>
      </section>

      <section className="product-shot">
        <div className="product-panel">
          <div className="product-head">
            <strong>Kontexa Workspace</strong>
            <small>Tenant-aware analytics view</small>
          </div>
          <div className="product-mock-grid">
            <article>
              <p>Rows returned</p>
              <strong>962</strong>
            </article>
            <article>
              <p>Avg latency</p>
              <strong>1.8s</strong>
            </article>
            <article>
              <p>Active tenants</p>
              <strong>12</strong>
            </article>
            <article>
              <p>Data sources</p>
              <strong>Snowflake, BQ, Redshift</strong>
            </article>
          </div>
        </div>
      </section>

      <section className="trusted">
        <p>Trusted by modern data teams</p>
        <div className="trusted-logos">
          <span>NETFLIX</span>
          <span>MSCI</span>
          <span>GOOGLE</span>
          <span>MICROSOFT</span>
          <span>ACME</span>
        </div>
      </section>

      <section id="how-it-works" className="steps">
        <article>
          <h3>1. Connect</h3>
          <p>Tenant admin links their cloud warehouse once and controls access centrally.</p>
        </article>
        <article>
          <h3>2. Ask</h3>
          <p>Users ask natural language questions with tenant schema context auto-resolved.</p>
        </article>
        <article>
          <h3>3. Decide</h3>
          <p>Get accurate SQL, complete tables, charts, and graphs in a single workspace.</p>
        </article>
      </section>
    </div>
  );
}

export default HomePage;
