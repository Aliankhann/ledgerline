import type { YearView } from "./api";
import { formatMoney } from "./money";

const NOT_INCLUDED = [
  "Other income: dividends, interest, return of capital income, fees, T-slip boxes",
  "T5008 or any broker slip reconciliation",
  "Foreign property reporting (T1135) and foreign-currency gain or loss on cash",
  "Registered accounts (TFSA, RRSP): their dispositions are not reported",
  "Carry-forward or carry-back of net capital losses, reserves, lifetime exemption, tax payable",
];

function Card({ label, value }: { label: string; value: string }) {
  return (
    <div style={{ border: "1px solid gray", borderRadius: 6, padding: "0.5rem 1rem" }}>
      <small>{label}</small>
      <div style={{ fontSize: "1.25rem" }}>{value}</div>
    </div>
  );
}

type Props = {
  taxpayer: string;
  years: number[];
  year: number | null;
  view: YearView | null;
  error: string | null;
  onYear: (year: number) => void;
};

export function YourYear({ taxpayer, years, year, view, error, onYear }: Props) {
  const base = `/api/v1/tax-year/${encodeURIComponent(taxpayer)}`;

  return (
    <section>
      <h2>Your year</h2>
      {error && <p role="alert">Could not load: {error}</p>}
      {year !== null && (
        <p>
          <label>
            Tax year{" "}
            <select value={year} onChange={(e) => onYear(Number(e.target.value))}>
              {years.map((y) => (
                <option key={y} value={y}>
                  {y}
                </option>
              ))}
            </select>
          </label>{" "}
          <a href={`${base}/${year}.csv`}>Download CSV</a>
        </p>
      )}
      {year === null && !error && <p>No tax years for {taxpayer} yet. Upload activities first.</p>}
      {view && (
        <>
          <div style={{ display: "flex", gap: "1rem", flexWrap: "wrap" }}>
            <Card label="Total gains" value={formatMoney(view.totals.gainsCents)} />
            <Card label="Total losses" value={formatMoney(view.totals.lossesCents)} />
            <Card label="Net" value={formatMoney(view.totals.netCents)} />
            <Card label="Taxable capital gain" value={formatMoney(view.totals.taxableCapitalGainCents)} />
            <Card label="Superficial losses denied" value={formatMoney(view.totals.superficialLossesDeniedCents)} />
          </div>
          {view.rows.length === 0 && <p>No sales in {view.year}.</p>}
          {view.rows.length > 0 && (
            <table>
              <thead>
                <tr>
                  <th>Tax date</th>
                  <th>Security</th>
                  <th>Shares (10^-6)</th>
                  <th>Proceeds</th>
                  <th>ACB sold</th>
                  <th>Outlays</th>
                  <th>Gain or loss</th>
                  <th>Superficial loss denied</th>
                  <th>Notes</th>
                </tr>
              </thead>
              <tbody>
                {view.rows.map((r) => (
                  <tr key={r.activityId}>
                    <td>{r.taxDate}</td>
                    <td>
                      {r.securityId}
                      {r.currency && r.currency !== "CAD" ? ` (${r.currency}, not converted; not in totals)` : ""}
                    </td>
                    <td style={{ textAlign: "right" }}>{r.sharesUnits}</td>
                    <td style={{ textAlign: "right" }}>{formatMoney(r.proceedsCents)}</td>
                    <td style={{ textAlign: "right" }}>{formatMoney(r.acbSoldCents)}</td>
                    <td style={{ textAlign: "right" }}>{formatMoney(r.outlaysCents)}</td>
                    <td style={{ textAlign: "right" }}>{formatMoney(r.gainCents)}</td>
                    <td style={{ textAlign: "right" }}>{formatMoney(r.superficialLossDeniedCents)}</td>
                    <td>{r.notes.join("; ")}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}
          {view.warnings.length > 0 && (
            <div>
              <h3>Data gaps</h3>
              <ul>
                {view.warnings.map((w) => (
                  <li key={w.ruleId}>
                    <small>{w.ruleId}</small> {w.text} (entries {w.entryIds.join(", ")})
                  </li>
                ))}
              </ul>
            </div>
          )}
        </>
      )}
      <div>
        <h3>Not included</h3>
        <ul>
          {NOT_INCLUDED.map((t) => (
            <li key={t}>{t}</li>
          ))}
        </ul>
      </div>
    </section>
  );
}
