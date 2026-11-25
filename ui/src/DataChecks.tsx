import type { Check } from "./api";

/** One plain-language next step per rule id; wording is provisional until the owner confirms it. */
// OWNER-INPUT: L-23 "What to do" wording (static map keyed by rule id)
const WHAT_TO_DO: Record<string, string> = {
  "DATA-GAP-TRANSFER-NO-COST": "Add the cost of the transferred shares, or treat the ACB as unknown",
  "DATA-GAP-OVERSELL": "Add the missing purchase or remove the sale",
  "FX-NOT-CONVERTED": "Foreign-currency rows are excluded from totals; convert them yourself for now",
  "FX-RATE-MISSING": "Add the Bank of Canada rate file for the trade date, or correct the trade date",
  AMOUNT_OUT_OF_RANGE: "Split or correct the very large amount in the listed rows",
  REJECTED: "Fix the row named and upload again",
};

const FALLBACK = "Review the trades listed and correct or add the missing activity";

type Props = {
  checks: Check[];
  onOpenEntry: (security: string, entryId: number) => void;
};

export function DataChecks({ checks, onOpenEntry }: Props) {
  if (checks.length === 0) return <p className="empty">No data checks to show</p>;
  return (
    <ul className="checks">
      {checks.map((c, i) => (
        <li key={i} className="check">
          <div>
            <span className="badge">{c.ruleId}</span> {c.text}
          </div>
          {(c.entryIds.length > 0 || c.lines.length > 0) && (
            <div className="trades">
              Trades:{" "}
              {c.entryIds.map((id) => (
                <button
                  key={id}
                  type="button"
                  className="chip"
                  data-entry-id={id}
                  disabled={c.securityId === null}
                  onClick={() => c.securityId !== null && onOpenEntry(c.securityId, id)}
                >
                  #{id}
                </button>
              ))}
              {c.lines.length > 0 && <span>file line {c.lines.join(", ")}</span>}
            </div>
          )}
          <div className="todo">
            <strong>What to do:</strong> {WHAT_TO_DO[c.ruleId] ?? FALLBACK}
          </div>
        </li>
      ))}
    </ul>
  );
}
