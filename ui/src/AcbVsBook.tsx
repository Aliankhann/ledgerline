import { useEffect, useState } from "react";
import type { BookView } from "./api";
import { BADGES, ExplainPanel } from "./ExplainPanel";
import { formatMoney } from "./money";

export { formatMoney };

/** The slice of the /explain payload the list needs; null means that holding failed to load. */
type Verdict = { status: string; fullyExplained: boolean; unexplainedCents: number; adjustmentCount: number };

type Props = {
  taxpayer: string;
  view: BookView | null;
  error: string | null;
  open: { security: string; entryId: number | null } | null;
  onOpen: (security: string) => void;
};

/** A missing value (no broker book value) is shown as a dash. */
function formatOptional(cents: number | null): string {
  return cents === null ? "—" : formatMoney(cents);
}

export function AcbVsBook({ taxpayer, view, error, open, onOpen }: Props) {
  // One /explain call per holding supplies the badge and the footer; absent key = still loading.
  // OWNER-INPUT: L-23 holdings badge source (one /explain request per holding, no backend change)
  const [verdicts, setVerdicts] = useState<Record<string, Verdict | null>>({});

  useEffect(() => {
    let live = true;
    setVerdicts({});
    if (!view) return;
    for (const h of view.holdings) {
      fetch(`/api/v1/holdings/${encodeURIComponent(taxpayer)}/${encodeURIComponent(h.securityId)}/explain`)
        .then((r) => (r.ok ? r.json() : Promise.reject(new Error(`HTTP ${r.status}`))))
        .then((d: Verdict) => live && setVerdicts((prev) => ({ ...prev, [h.securityId]: d })))
        .catch(() => live && setVerdicts((prev) => ({ ...prev, [h.securityId]: null })));
    }
    return () => {
      live = false;
    };
  }, [taxpayer, view]);

  // Footer inputs: only holdings with a book value are compared.
  // OWNER-INPUT: L-23 strict "fully explained" footer (every holding with a book value; no book value = not compared)
  const compared: string[] = [];
  let settled = 0;
  if (view) {
    for (const h of view.holdings) {
      if (h.bookValueCents !== null) compared.push(h.securityId);
      if (verdicts[h.securityId] !== undefined) settled += 1;
    }
  }
  const allSettled = view !== null && settled === view.holdings.length;
  const allExplained = compared.length > 0 && compared.every((id) => verdicts[id]?.fullyExplained === true);
  const notExplained: string[] = [];
  for (const id of compared) {
    if (verdicts[id]?.fullyExplained !== true) notExplained.push(id);
  }

  return (
    <section>
      <h2>Book value vs. ACB</h2>
      {error && <p role="alert">Could not load: {error}</p>}
      {!view && !error && <p>Loading…</p>}
      {view && view.holdings.length === 0 && <p>No holdings to compare for {view.taxpayerId}.</p>}
      {view && view.holdings.length > 0 && (
        <table className="grid">
          <thead>
            <tr>
              <th>Security</th>
              <th>Status</th>
              <th>Book value ({view.currency})</th>
              <th>ACB ({view.currency})</th>
              <th>Difference</th>
              <th>Reasons</th>
            </tr>
          </thead>
          <tbody>
            {view.holdings.map((h) => {
              const v = verdicts[h.securityId];
              return (
                <tr key={h.securityId} onClick={() => onOpen(h.securityId)} style={{ cursor: "pointer" }}>
                  <td>{h.securityId}</td>
                  <td>
                    {v === undefined && <small className="muted">…</small>}
                    {v === null && <small className="warn">Not loaded</small>}
                    {v && <span className="badge">{BADGES[v.status] ?? v.status}</span>}
                    {v && h.differenceCents !== null && !v.fullyExplained && (
                      <div>
                        <small>{formatMoney(v.unexplainedCents)} unexplained</small>
                      </div>
                    )}
                  </td>
                  <td className="num">{formatOptional(h.bookValueCents)}</td>
                  <td className="num">{formatMoney(h.acbCents)}</td>
                  <td className="num">{formatOptional(h.differenceCents)}</td>
                  <td>
                    {h.reasons.length === 0 ? (
                      "No difference"
                    ) : (
                      <ul>
                        {h.reasons.map((r) => (
                          <li key={r.ruleId}>
                            <small className="badge">{r.ruleId}</small> {formatMoney(r.amountMinor)}: {r.text}
                          </li>
                        ))}
                      </ul>
                    )}
                  </td>
                </tr>
              );
            })}
          </tbody>
        </table>
      )}
      {view && view.holdings.length > 0 && allSettled && (
        <p className="footer">
          {allExplained && notExplained.length === 0 && <strong>All {compared.length} holdings fully explained</strong>}
          {notExplained.length > 0 && <strong>Not explained: {notExplained.join(", ")}</strong>}
          {compared.length === 0 && <span>No holdings have a book value to compare.</span>}
        </p>
      )}
      {view && open && (
        <ExplainPanel taxpayer={view.taxpayerId} security={open.security} focusEntry={open.entryId} />
      )}
    </section>
  );
}
