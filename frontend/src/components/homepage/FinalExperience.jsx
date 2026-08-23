export default function FinalExperience({ onRequestAccess }) {
  return (
    <section className="hx-exp hx-final">
      <div className="hx-final-statement">
        <p className="hx-final-line hx-final-line--xl">
          Your warehouse already contains the answers.
        </p>
        <p className="hx-final-line">
          The challenge isn&apos;t finding more data. It&apos;s finding what matters inside it.
        </p>
        <p className="hx-final-line hx-final-line--brand">
          <span>KONTEXA</span> surfaces the signals worth acting on.
        </p>
      </div>

      <div className="hx-final-cta">
        <h2 className="hx-closing">
          Your data has answers.<br />
          Kontexa surfaces them.
        </h2>
        <div className="hx-actions">
          <button type="button" className="hp-btn hp-btn--primary hp-btn--lg" onClick={onRequestAccess}>
            Request Enterprise Access
          </button>
          <a href="#how" className="hp-btn hp-btn--ghost hp-btn--lg">
            See how it works →
          </a>
        </div>
      </div>
    </section>
  );
}
