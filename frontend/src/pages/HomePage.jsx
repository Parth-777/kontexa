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
        <div className="home-nav-links">
          <a href="#features">Features</a>
          <a href="#how-it-works">How It Works</a>
          <Link to="/signin" className="nav-link">
            Get Access
          </Link>
        </div>
      </nav>

      <section className="hero">
        <span className="hero-pill">Now in Early Access</span>
        <h1>
          Ask your data
          <br />
          <span>anything.</span>
        </h1>
        <p>
          KONTEXA is the intelligence layer for your analytics stack. Query Amplitude, Mixpanel, or
          PostHog with natural language - get charts, insights, and explanations in seconds.
        </p>
        <div className="hero-query-box">
          <b>›</b>
          <span>{typedText || ' '}</span>
          <i className="cursor" />
        </div>
        <div className="hero-actions">
          <Link to="/signin" className="hero-btn primary">
            Get Early Access
          </Link>
          <a href="#how-it-works" className="hero-btn secondary">
            See how it works
          </a>
        </div>
      </section>

      <section id="features" className="features-grid">
        <article>
          <span className="feature-icon">💬</span>
          <h3>Natural Language Queries</h3>
          <p>Ask questions in plain English. No SQL, no dashboards to configure, no query builders.</p>
        </article>
        <article>
          <span className="feature-icon">🧠</span>
          <h3>Semantic Ontology Layer</h3>
          <p>Bridges raw event data to human concepts - understands your product domain automatically.</p>
        </article>
        <article>
          <span className="feature-icon">📊</span>
          <h3>Instant Visualizations</h3>
          <p>Get charts, tables, and explanations. Every result includes assumptions and methodology.</p>
        </article>
        <article>
          <span className="feature-icon">⚡</span>
          <h3>Multi-Source Intelligence</h3>
          <p>Connects to Amplitude, Mixpanel, PostHog, and data warehouses - query all at once.</p>
        </article>
        <article>
          <span className="feature-icon">🛡️</span>
          <h3>Transparent Assumptions</h3>
          <p>Every answer shows its reasoning. Know exactly what data was used and how it was interpreted.</p>
        </article>
        <article>
          <span className="feature-icon">🔗</span>
          <h3>Reverse Instrumentation</h3>
          <p>Identifies gaps in your tracking plan and suggests events you should be capturing.</p>
        </article>
      </section>

      <section id="how-it-works" className="final-cta">
        <h2>Ready to talk to your data?</h2>
        <p>
          Join the early access program. Be among the first teams to unlock natural language
          analytics.
        </p>
        <Link to="/signin" className="hero-btn primary">
          Request Access
        </Link>
      </section>

      <footer className="home-footer">
        <strong>KONTEXA</strong>
        <span>© 2026 Kontexa. All rights reserved.</span>
      </footer>
    </div>
  );
}

export default HomePage;
