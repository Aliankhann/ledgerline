import type { ReactNode } from "react";
import type { Check } from "./api";
import { formatMoney } from "./money";

type Props = {
  taxpayer: string;
  onTaxpayer: (value: string) => void;
  years: number[];
  year: number | null;
  onYear: (year: number) => void;
  netCents: number | null;
  holdingCount: number | null;
  checks: Check[];
  warningCount: number;
  healthStatus: string | null;
  healthError: string | null;
};

function Field({ label, children }: { label: string; children: ReactNode }) {
  return (
    <div className="field">
      <small>{label}</small>
      <div className="value">{children}</div>
    </div>
  );
}

export function SummaryBar(p: Props) {
  const fxExcluded = p.checks.some((c) => c.ruleId === "FX-NOT-CONVERTED");
  const healthy = p.healthStatus !== null;
  return (
    <div className="summary" role="region" aria-label="Summary">
      <Field label="Taxpayer">
        <input aria-label="Taxpayer" value={p.taxpayer} onChange={(e) => p.onTaxpayer(e.target.value)} />
      </Field>
      <Field label="Tax year">
        {p.years.length === 0 ? (
          "—"
        ) : (
          <select aria-label="Tax year" value={p.year ?? ""} onChange={(e) => p.onYear(Number(e.target.value))}>
            {p.years.map((y) => (
              <option key={y} value={y}>
                {y}
              </option>
            ))}
          </select>
        )}
      </Field>
      <Field label="Net gain or loss">
        {p.netCents === null ? "—" : formatMoney(p.netCents)}
        {fxExcluded && <small className="muted"> CAD only</small>}
      </Field>
      <Field label="Holdings">{p.holdingCount === null ? "—" : String(p.holdingCount)}</Field>
      <Field label="Warnings">
        <span className={p.warningCount > 0 ? "warn" : undefined}>{String(p.warningCount)}</span>
      </Field>
      <Field label="Health">
        {healthy ? (
          <span className="ok">{p.healthStatus}</span>
        ) : (
          <span className="warn">{p.healthError ? `unreachable: ${p.healthError}` : "checking…"}</span>
        )}
      </Field>
    </div>
  );
}
