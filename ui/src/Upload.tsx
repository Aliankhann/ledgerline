import { useRef, useState } from "react";
import type { ChangeEvent } from "react";
import { formatMoney } from "./money";

type Status = "NEW" | "DUPLICATE" | "CONFLICT" | "REJECTED";
type Row = {
  line: number;
  key: string | null;
  type: string | null;
  date: string | null;
  status: Status;
  code: string | null;
  field: string | null;
  message: string | null;
};
type Warning = {
  ruleId: string;
  securityId: string;
  text: string;
  lines: number[];
  amountMinor: number;
};
type FileError = { code: string; message: string; column: string | null };
export type Report = {
  fileSha256: string;
  rowsRead: number;
  new: number;
  duplicates: number;
  conflicts: number;
  rejected: number;
  rows: Row[];
  warnings: Warning[];
  fileErrors: FileError[];
  format: "CANONICAL" | "BROKER_B" | null;
};
type ConfirmResult = Report & {
  posted: number;
  changedSincePreview: boolean;
  changedRows: { line: number; previewStatus: Status; status: Status }[];
};

// Rejected and conflicting rows first, then the rest by line; nothing is dropped.
const URGENCY: Record<Status, number> = {
  REJECTED: 0,
  CONFLICT: 1,
  NEW: 2,
  DUPLICATE: 3,
};

function Card({ label, value }: { label: string; value: number }) {
  return (
    <div
      style={{
        border: "1px solid gray",
        borderRadius: 6,
        padding: "0.5rem 1rem",
      }}
    >
      <small>{label}</small>
      <div style={{ fontSize: "1.25rem" }}>{String(value)}</div>
    </div>
  );
}

function Cards({ r }: { r: Report }) {
  return (
    <div style={{ display: "flex", gap: "0.75rem", flexWrap: "wrap" }}>
      <Card label="Rows read" value={r.rowsRead} />
      <Card label="New" value={r.new} />
      <Card label="Duplicates skipped" value={r.duplicates} />
      <Card label="Conflicts" value={r.conflicts} />
      <Card label="Rejected" value={r.rejected} />
    </div>
  );
}

const ACCOUNT_TYPES = ["NON_REGISTERED", "TFSA", "RRSP"];

function asJson<T>(r: Response): Promise<T> {
  return r.ok ? (r.json() as Promise<T>) : Promise.reject(new Error(`HTTP ${r.status}`));
}

type UploadProps = {
  report: Report | null;
  setReport: (r: Report | null) => void;
  taxpayer: string;
};

export function Upload({ report, setReport, taxpayer }: UploadProps) {
  const [text, setText] = useState<string | null>(null);
  const [result, setResult] = useState<ConfirmResult | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [accountType, setAccountType] = useState("NON_REGISTERED");
  const [applied, setApplied] = useState("");
  const inFlight = useRef(false);
  const choice = useRef(0);

  function query(): string {
    return `taxpayer=${encodeURIComponent(taxpayer.trim())}&accountType=${encodeURIComponent(accountType)}`;
  }

  function runPreview(body: string, mine: number, q: string) {
    setBusy(true);
    fetch(`/api/v1/imports/preview${q ? `?${q}` : ""}`, {
      method: "POST",
      headers: { "Content-Type": "text/csv" },
      body,
    })
      .then((r) => asJson<Report>(r))
      .then((rep) => {
        if (mine !== choice.current) return;
        setText(body);
        setApplied(q);
        setReport(rep);
      })
      .catch((err: unknown) => {
        if (mine === choice.current) setError(err instanceof Error ? err.message : String(err));
      })
      .finally(() => {
        if (mine === choice.current) setBusy(false);
      });
  }

  function onFile(e: ChangeEvent<HTMLInputElement>) {
    const file = e.target.files?.[0];
    e.target.value = ""; // so choosing the same file again fires onChange again
    const mine = ++choice.current;
    setReport(null);
    setResult(null);
    setError(null);
    setText(null);
    if (!file) return;
    setBusy(true);
    file
      .text()
      .then((body) => runPreview(body, mine, ""))
      .catch((err: unknown) => {
        if (mine === choice.current) {
          setError(err instanceof Error ? err.message : String(err));
          setBusy(false);
        }
      });
  }

  function previewAgain() {
    if (text === null) return;
    setError(null);
    runPreview(text, ++choice.current, query());
  }

  function confirm() {
    if (!report || text === null || inFlight.current) return;
    inFlight.current = true;
    setBusy(true);
    const q = applied;
    const newLines: number[] = [];
    for (const row of report.rows) {
      if (row.status === "NEW") newLines.push(row.line);
    }
    fetch(
      `/api/v1/imports/confirm?fileSha256=${report.fileSha256}&previewNew=${newLines.join(",")}${q ? `&${q}` : ""}`,
      {
        method: "POST",
        headers: { "Content-Type": "text/csv" },
        body: text,
      },
    )
      .then((r) => asJson<ConfirmResult>(r))
      .then((res) => {
        setResult(res);
        setReport(null);
      })
      .catch((err: unknown) => setError(err instanceof Error ? err.message : String(err)))
      .finally(() => {
        inFlight.current = false;
        setBusy(false);
      });
  }

  const rows = report ? [...report.rows].sort((a, b) => URGENCY[a.status] - URGENCY[b.status] || a.line - b.line) : [];

  return (
    <section>
      <h2>Upload</h2>
      <p>Choose an activity CSV. Nothing is posted until you confirm.</p>
      <input type="file" accept=".csv,text/csv" onChange={onFile} />
      {busy && <p>Working…</p>}
      {error && <p role="alert">Request failed: {error}</p>}
      {report && (
        <div>
          <p>Detected format: {report.format ?? "unknown"}</p>
          {report.format === "BROKER_B" && (
            <div>
              <span>Taxpayer: {taxpayer}</span>{" "}
              <label>
                Account type{" "}
                <select
                  aria-label="Account type for this taxpayer"
                  value={accountType}
                  onChange={(e) => setAccountType(e.target.value)}
                >
                  {ACCOUNT_TYPES.map((t) => (
                    <option key={t} value={t}>
                      {t}
                    </option>
                  ))}
                </select>
              </label>{" "}
              <button
                type="button"
                disabled={busy || taxpayer.trim() === "" || accountType === ""}
                onClick={previewAgain}
              >
                Preview with these answers
              </button>
            </div>
          )}
          <Cards r={report} />
          {report.fileErrors.map((f) => (
            <p key={f.code + String(f.column)} role="alert">
              {f.code}: {f.message}
            </p>
          ))}
          <h3>Rows</h3>
          <table>
            <thead>
              <tr>
                <th>Line</th>
                <th>Key</th>
                <th>Type</th>
                <th>Date</th>
                <th>Status</th>
                <th>Reason</th>
              </tr>
            </thead>
            <tbody>
              {rows.map((r) => (
                <tr
                  key={r.line}
                  data-status={r.status}
                  data-line={r.line}
                  style={r.status === "REJECTED" || r.status === "CONFLICT" ? { fontWeight: "bold" } : undefined}
                >
                  <td>{r.line}</td>
                  <td>{r.key ?? ""}</td>
                  <td>{r.type ?? ""}</td>
                  <td>{r.date ?? ""}</td>
                  <td>{r.status}</td>
                  <td>{r.code ? `${r.code}${r.field ? ` (${r.field})` : ""}: ${r.message ?? ""}` : ""}</td>
                </tr>
              ))}
            </tbody>
          </table>
          <h3>Warnings</h3>
          {report.warnings.length === 0 && <p>None.</p>}
          <ul>
            {report.warnings.map((w) => (
              <li key={w.ruleId + w.securityId}>
                {w.ruleId} on {w.securityId}, line {w.lines.join(", ")}: {w.text} (amount {formatMoney(w.amountMinor)})
              </li>
            ))}
          </ul>
        </div>
      )}
      {(report || result) && (
        <button
          type="button"
          disabled={busy || !report || report.new === 0 || report.fileErrors.length > 0}
          onClick={confirm}
        >
          Confirm import
        </button>
      )}
      {result && (
        <div>
          <h3>Import result</h3>
          <p>
            Posted {result.posted}, duplicates {result.duplicates}, conflicts {result.conflicts}, rejected{" "}
            {result.rejected}.{result.changedSincePreview && " The ledger changed since the preview; some rows moved."}
          </p>
          <Cards r={result} />
        </div>
      )}
    </section>
  );
}
