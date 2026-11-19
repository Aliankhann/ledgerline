import { useEffect, useState } from "react";

type Health = { status: string; modules: string[] };

// Placeholder shell. Story L-11 replaces this with the book value vs true ACB view.
export function App() {
  const [health, setHealth] = useState<Health | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    fetch("/api/health")
      .then((r) => (r.ok ? r.json() : Promise.reject(new Error(`HTTP ${r.status}`))))
      .then((h: Health) => setHealth(h))
      .catch((e: unknown) => setError(e instanceof Error ? e.message : String(e)));
  }, []);

  return (
    <main style={{ fontFamily: "system-ui, sans-serif", maxWidth: 720, margin: "2rem auto", padding: "0 1rem" }}>
      <h1>Ledgerline</h1>
      <p>Book value vs. true adjusted cost base, with a reason for every difference.</p>
      <section>
        <h2>Service</h2>
        {error && <p>Service unreachable: {error}. Start it with <code>make up</code>.</p>}
        {health && (
          <p>
            Status <strong>{health.status}</strong>; modules: {health.modules.join(", ")}
          </p>
        )}
        {!error && !health && <p>Checking…</p>}
      </section>
    </main>
  );
}
