import { useEffect, useState } from "react";
import { AcbVsBook } from "./AcbVsBook";
import { buildChecks } from "./checks";
import { DataChecks } from "./DataChecks";
import { SummaryBar } from "./SummaryBar";
import { Upload } from "./Upload";
import type { Report } from "./Upload";
import { useTaxpayerData } from "./useTaxpayerData";
import { YourYear } from "./YourYear";

type Health = { status: string; modules: string[] };
type Tab = "upload" | "your-year" | "holdings" | "data-checks";

const TABS: { slug: Tab; label: string }[] = [
  { slug: "upload", label: "Upload" },
  { slug: "your-year", label: "Your year" },
  { slug: "holdings", label: "Holdings" },
  { slug: "data-checks", label: "Data checks" },
];

function tabFromHash(): Tab | null {
  const slug = window.location.hash.replace("#", "");
  for (const t of TABS) {
    if (t.slug === slug) return t.slug;
  }
  return null;
}

export function App() {
  const [taxpayer, setTaxpayer] = useState("TP-0001");
  const [explicit, setExplicit] = useState<Tab | null>(tabFromHash);
  const [report, setReport] = useState<Report | null>(null);
  const [open, setOpen] = useState<{ security: string; entryId: number | null } | null>(null);
  const [health, setHealth] = useState<Health | null>(null);
  const [healthError, setHealthError] = useState<string | null>(null);
  const data = useTaxpayerData(taxpayer);

  useEffect(() => {
    fetch("/api/health")
      .then((r) => (r.ok ? r.json() : Promise.reject(new Error(`HTTP ${r.status}`))))
      .then((h: Health) => setHealth(h))
      .catch((e: unknown) => setHealthError(e instanceof Error ? e.message : String(e)));
  }, []);

  useEffect(() => {
    const onHash = () => setExplicit(tabFromHash());
    window.addEventListener("hashchange", onHash);
    return () => window.removeEventListener("hashchange", onHash);
  }, []);

  // An explicit hash always wins; otherwise "Your year" once a tax year exists, else "Upload".
  // OWNER-INPUT: L-23 default tab rule (a taxpayer with holdings but no tax year lands on Upload)
  const tab: Tab = explicit ?? (data.years.length > 0 ? "your-year" : "upload");

  function changeTaxpayer(value: string) {
    setTaxpayer(value);
    setOpen(null);
  }

  function select(slug: Tab) {
    window.location.hash = slug;
  }

  function openEntry(security: string, entryId: number) {
    setOpen({ security, entryId });
    select("holdings");
  }

  // One list feeds both the summary bar count and the Data checks tab.
  const checks = buildChecks(data.book, data.yearView, data.refusal, report);

  return (
    <main className="app">
      <header>
        <h1>Ledgerline</h1>
        <p className="muted">Book value vs. true adjusted cost base, with a reason for every difference.</p>
      </header>
      <SummaryBar
        taxpayer={taxpayer}
        onTaxpayer={changeTaxpayer}
        years={data.years}
        year={data.year}
        onYear={data.setYear}
        netCents={data.yearView ? data.yearView.totals.netCents : null}
        holdingCount={data.book ? data.book.holdings.length : null}
        checks={checks}
        warningCount={checks.length}
        healthStatus={health ? health.status : null}
        healthError={healthError}
      />
      <div role="tablist" aria-label="Dashboard" className="tabs">
        {TABS.map((t) => (
          <button
            key={t.slug}
            role="tab"
            id={`tab-${t.slug}`}
            aria-selected={tab === t.slug}
            aria-controls="tabpanel"
            className={tab === t.slug ? "tab active" : "tab"}
            onClick={() => select(t.slug)}
          >
            {t.label}
            {t.slug === "data-checks" && checks.length > 0 ? ` (${checks.length})` : ""}
          </button>
        ))}
      </div>
      <div role="tabpanel" id="tabpanel" aria-labelledby={`tab-${tab}`} className="panel">
        {tab === "upload" && <Upload report={report} setReport={setReport} />}
        {tab === "your-year" && (
          <YourYear
            key={taxpayer}
            taxpayer={taxpayer}
            years={data.years}
            year={data.year}
            view={data.yearView}
            error={data.refusal ? `${data.refusal.error}: ${data.refusal.message}` : data.yearError}
            onYear={data.setYear}
          />
        )}
        {tab === "holdings" && (
          <AcbVsBook
            key={taxpayer}
            taxpayer={taxpayer}
            view={data.book}
            error={data.bookError}
            open={open}
            onOpen={(security) => setOpen({ security, entryId: null })}
          />
        )}
        {tab === "data-checks" && <DataChecks checks={checks} onOpenEntry={openEntry} />}
      </div>
    </main>
  );
}
