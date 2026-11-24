import { useState } from "react";
import { ExplainPanel } from "./ExplainPanel";
import { formatMoney } from "./money";

export { formatMoney };

type Reason = { ruleId: string; text: string; entryIds: number[]; amountMinor: number };
type Holding = {
  securityId: string;
  bookValueCents: number | null;
  acbCents: number;
  differenceCents: number | null;
  reasons: Reason[];
};
type View = { taxpayerId: string; currency: string; holdings: Holding[] };

/** A missing value (no broker book value) is shown as a dash. */
function formatOptional(cents: number | null): string {
  return cents === null ? "\u2014" : formatMoney(cents);
}

export function AcbVsBook() {
  const [taxpayer, setTaxpayer] = useState("TP-0001");
  const [view, setView] = useState<View | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [open, setOpen] = useState<string | null>(null);

  function load() {
    setError(null);
    setOpen(null);
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
              <tr key={h.securityId} onClick={() => setOpen(h.securityId)} style={{ cursor: "pointer" }}>
                <td>{h.securityId}</td>
                <td style={{ textAlign: "right" }}>{formatOptional(h.bookValueCents)}</td>
                <td style={{ textAlign: "right" }}>{formatMoney(h.acbCents)}</td>
                <td style={{ textAlign: "right" }}>{formatOptional(h.differenceCents)}</td>
                <td>
                  {h.reasons.length === 0 ? (
                    "No difference"
                  ) : (
                    <ul>
                      {h.reasons.map((r) => (
                        <li key={r.ruleId}>
                          <small className="badge" style={{ border: "1px solid gray", borderRadius: 4, padding: "0 4px" }}>{r.ruleId}</small>{" "}
                          {formatMoney(r.amountMinor)}: {r.text}
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
      {view && open && <ExplainPanel taxpayer={view.taxpayerId} security={open} />}
    </section>
  );
}
