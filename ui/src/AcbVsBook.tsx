import { useState } from "react";

type Reason = { ruleId: string; text: string; entryIds: number[]; amountMinor: number };
type Holding = {
  securityId: string;
  bookValueCents: number;
  acbCents: number;
  differenceCents: number;
  reasons: Reason[];
};
type View = { taxpayerId: string; currency: string; holdings: Holding[] };

/** Minor units (cents) to "-1,234.05" with integer math only. */
export function formatMoney(cents: number): string {
  const sign = cents < 0 ? "-" : "";
  const abs = Math.abs(cents);
  const dollars = Math.trunc(abs / 100);
  const rest = abs - dollars * 100;
  const grouped = String(dollars).replace(/\B(?=(\d{3})+(?!\d))/g, ",");
  return `${sign}${grouped}.${rest < 10 ? "0" : ""}${rest}`;
}

export function AcbVsBook() {
  const [taxpayer, setTaxpayer] = useState("TP-0001");
  const [view, setView] = useState<View | null>(null);
  const [error, setError] = useState<string | null>(null);

  function load() {
    setError(null);
    fetch(`/api/v1/holdings/${encodeURIComponent(taxpayer)}/acb-vs-book`)
      .then((r) => (r.ok ? r.json() : Promise.reject(new Error(`HTTP ${r.status}`))))
      .then((v: View) => setView(v))
      .catch((e: unknown) => {
        setView(null);
        setError(e instanceof Error ? e.message : String(e));
      });
  }

  return (
    <section>
      <h2>Book value vs. ACB</h2>
      <label>
        Taxpayer <input value={taxpayer} onChange={(e) => setTaxpayer(e.target.value)} />
      </label>{" "}
      <button onClick={load}>Load</button>
      {error && <p>Could not load: {error}</p>}
      {view && view.holdings.length === 0 && <p>No holdings to compare for {view.taxpayerId}.</p>}
      {view && view.holdings.length > 0 && (
        <table>
          <thead>
            <tr>
              <th>Security</th>
              <th>Book value ({view.currency})</th>
              <th>ACB ({view.currency})</th>
              <th>Difference</th>
              <th>Reasons</th>
            </tr>
          </thead>
          <tbody>
            {view.holdings.map((h) => (
              <tr key={h.securityId}>
                <td>{h.securityId}</td>
                <td style={{ textAlign: "right" }}>{formatMoney(h.bookValueCents)}</td>
                <td style={{ textAlign: "right" }}>{formatMoney(h.acbCents)}</td>
                <td style={{ textAlign: "right" }}>{formatMoney(h.differenceCents)}</td>
                <td>
                  {h.reasons.length === 0 ? (
                    "No difference"
                  ) : (
                    <ul>
                      {h.reasons.map((r) => (
                        <li key={r.ruleId}>
                          <code>{r.ruleId}</code> {formatMoney(r.amountMinor)}: {r.text}
                        </li>
                      ))}
                    </ul>
                  )}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </section>
  );
}
