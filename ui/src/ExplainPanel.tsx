import { useEffect, useState } from "react";
import { formatMoney } from "./money";

type Reason = { ruleId: string; text: string; entryIds: number[]; amountMinor: number };
type SuperficialLoss = {
  saleEntryId: number;
  targetEntryIds: number[];
  lossCents: number;
  deniedCents: number;
  addBackCents: number;
};
type Step = {
  entryId: number;
  idempotencyKey: string;
  taxDate: string;
  type: string;
  ruleIds: string[];
  sharesDeltaUnits: number;
  costDeltaCents: number;
  runningSharesUnits: number;
  runningAcbCents: number;
  superficialLoss: SuperficialLoss | null;
};
type Explanation = {
  securityId: string;
  bookValueCents: number | null;
  acbCents: number;
  differenceCents: number | null;
  status: string;
  fullyExplained: boolean;
  adjustmentCount: number;
  unexplainedCents: number;
  steps: Step[];
  reasons: Reason[];
};

const BADGES: Record<string, string> = {
  MATCHES: "Matches",
  EXPLAINED: "Explained",
  DISCREPANCY: "Discrepancy",
  DATA_GAP: "Data gap",
  NO_BOOK_VALUE: "No book value",
};

const LABELS: Record<string, string> = {
  "SL-ADDBACK-TARGET": "Superficial-loss add-back",
  "COST-BUY-COMMISSION": "Acquisition commission included in ACB",
  "ROC-REDUCES-ACB": "Return of capital reduced ACB",
};

/** Shares are in 10^-6 units; shown with integer math only. */
function formatShares(units: number): string {
  const sign = units < 0 ? "-" : "";
  const abs = Math.abs(units);
  const whole = Math.trunc(abs / 1000000);
  const frac = String(abs - whole * 1000000).padStart(6, "0");
  return `${sign}${whole}.${frac}`;
}

function formatOptional(cents: number | null): string {
  return cents === null ? "—" : formatMoney(cents);
}

const card = { border: "1px solid #bbb", borderRadius: 8, padding: 12, marginTop: 12 } as const;
const chip = { border: "1px solid gray", borderRadius: 12, padding: "0 8px", marginRight: 4, cursor: "pointer" } as const;
const num = { textAlign: "right" } as const;

export function ExplainPanel({ taxpayer, security }: { taxpayer: string; security: string }) {
  const [data, setData] = useState<Explanation | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [selected, setSelected] = useState<number | null>(null);

  useEffect(() => {
    setData(null);
    setError(null);
    setSelected(null);
    fetch(`/api/v1/holdings/${encodeURIComponent(taxpayer)}/${encodeURIComponent(security)}/explain`)
      .then((r) => (r.ok ? r.json() : Promise.reject(new Error(`HTTP ${r.status}`))))
      .then((d: Explanation) => setData(d))
      .catch((e: unknown) => setError(e instanceof Error ? e.message : String(e)));
  }, [taxpayer, security]);

  if (error) return <p>Could not explain {security}: {error}</p>;
  if (!data) return <p>Loading {security}...</p>;

  return (
    <div style={card}>
      <h3>
        {data.securityId}{" "}
        <small style={{ border: "1px solid gray", borderRadius: 4, padding: "0 6px" }}>
          {BADGES[data.status] ?? data.status}
        </small>
      </h3>
      <p>
        Broker book value: {formatOptional(data.bookValueCents)} · Calculated ACB: {formatMoney(data.acbCents)} ·
        Difference: {formatOptional(data.differenceCents)}
      </p>

      <h4>Explanation of difference</h4>
      {data.reasons.length === 0 ? (
        <p>No reasons to show.</p>
      ) : (
        <ul>
          {data.reasons.map((r) => (
            <li key={r.ruleId}>
              <strong>{LABELS[r.ruleId] ?? r.ruleId}</strong> {formatMoney(r.amountMinor)}: {r.text}{" "}
              {r.entryIds.map((id) => (
                <button key={id} data-entry-id={id} style={chip} onClick={() => setSelected(id)}>
                  #{id}
                </button>
              ))}
            </li>
          ))}
        </ul>
      )}
      {data.differenceCents !== null && (
        <p>
          <strong>
            {data.fullyExplained
              ? `Difference fully explained by ${data.adjustmentCount} adjustments`
              : `$${formatMoney(data.unexplainedCents)} unexplained`}
          </strong>
        </p>
      )}

      <h4>ACB timeline</h4>
      {data.steps.length === 0 ? (
        <p>No ledger steps for this holding.</p>
      ) : (
        <table>
          <thead>
            <tr>
              <th>Date</th>
              <th>Type</th>
              <th>Entry</th>
              <th>Rules</th>
              <th>Shares</th>
              <th>Cost change</th>
              <th>Running shares</th>
              <th>Running ACB</th>
            </tr>
          </thead>
          <tbody>
            {data.steps.map((s, i) => (
              <tr
                key={`${s.entryId}-${i}`}
                data-entry-id={s.entryId}
                data-highlighted={selected === s.entryId ? "true" : "false"}
                style={selected === s.entryId ? { background: "#fff3b0" } : undefined}
              >
                <td>{s.taxDate}</td>
                <td>{s.type}</td>
                <td title={s.idempotencyKey}>#{s.entryId}</td>
                <td>{s.ruleIds.join(", ")}</td>
                <td style={num}>{formatShares(s.sharesDeltaUnits)}</td>
                <td style={num}>{formatMoney(s.costDeltaCents)}</td>
                <td style={num}>{formatShares(s.runningSharesUnits)}</td>
                <td style={num}>{formatMoney(s.runningAcbCents)}</td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </div>
  );
}
