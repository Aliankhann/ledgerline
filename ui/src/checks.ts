import type { ApiRefusal, BookView, Check, YearView } from "./api";
import type { Report } from "./Upload";

/**
 * The single Data checks list. Every source is appended, nothing is dropped or merged:
 * data-gap reasons (L-16), tax-year warnings (L-18), the tax-year refusal (L-18),
 * and the last upload preview's warnings and rejected rows (L-20).
 */
export function buildChecks(
  book: BookView | null,
  year: YearView | null,
  refusal: ApiRefusal | null,
  report: Report | null,
): Check[] {
  const out: Check[] = [];
  if (book) {
    for (const h of book.holdings) {
      for (const r of h.reasons) {
        if (r.ruleId.startsWith("DATA-GAP")) {
          out.push({ ruleId: r.ruleId, text: r.text, entryIds: r.entryIds, securityId: h.securityId, lines: [] });
        }
      }
    }
  }
  if (year) {
    for (const w of year.warnings) {
      let security: string | null = null;
      for (const row of year.rows) {
        for (const id of w.entryIds) {
          if (security === null && row.entryIds.includes(id)) security = row.securityId;
        }
      }
      out.push({ ruleId: w.ruleId, text: w.text, entryIds: w.entryIds, securityId: security, lines: [] });
    }
  }
  if (refusal) {
    out.push({ ruleId: refusal.error, text: refusal.message, entryIds: [], securityId: null, lines: [] });
  }
  if (report) {
    for (const w of report.warnings) {
      out.push({ ruleId: w.ruleId, text: w.text, entryIds: [], securityId: w.securityId, lines: w.lines });
    }
    for (const row of report.rows) {
      if (row.status === "REJECTED") {
        const why = `${row.code ?? "Rejected"}${row.field ? ` (${row.field})` : ""}: ${row.message ?? ""}`;
        out.push({ ruleId: "REJECTED", text: why, entryIds: [], securityId: null, lines: [row.line] });
      }
    }
  }
  return out;
}
