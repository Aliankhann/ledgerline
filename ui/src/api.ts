export type Reason = { ruleId: string; text: string; entryIds: number[]; amountMinor: number };
export type Holding = {
  securityId: string;
  bookValueCents: number | null;
  acbCents: number;
  differenceCents: number | null;
  reasons: Reason[];
};
export type BookView = { taxpayerId: string; currency: string; holdings: Holding[] };

export type YearRow = {
  activityId: string;
  taxDate: string;
  securityId: string;
  currency?: string;
  sharesUnits: number;
  proceedsCents: number;
  outlaysCents: number;
  acbSoldCents: number;
  gainCents: number;
  superficialLossDeniedCents: number;
  notes: string[];
  entryIds: number[];
};
export type YearWarning = { ruleId: string; text: string; entryIds: number[] };
export type Totals = {
  gainsCents: number;
  lossesCents: number;
  netCents: number;
  taxableCapitalGainCents: number;
  superficialLossesDeniedCents: number;
};
export type YearView = { taxpayerId: string; year: number; rows: YearRow[]; totals: Totals; warnings: YearWarning[] };

/** The 422 body of the tax-year route (for example AMOUNT_OUT_OF_RANGE). */
export type ApiRefusal = { error: string; message: string };
export type YearResult = { view: YearView | null; refusal: ApiRefusal | null };

/** One line of the Data checks list, whatever its source. */
export type Check = {
  ruleId: string;
  text: string;
  entryIds: number[];
  securityId: string | null;
  lines: number[];
};

export function fetchJson<T>(url: string): Promise<T> {
  return fetch(url).then((r) => (r.ok ? r.json() : Promise.reject(new Error(`HTTP ${r.status}`))));
}

export function fetchYear(taxpayer: string, year: number): Promise<YearResult> {
  return fetch(`/api/v1/tax-year/${encodeURIComponent(taxpayer)}/${year}`).then(async (r) => {
    if (r.status === 422) {
      const body = (await r.json()) as ApiRefusal;
      return { view: null, refusal: body };
    }
    if (!r.ok) throw new Error(`HTTP ${r.status}`);
    return { view: (await r.json()) as YearView, refusal: null };
  });
}

export function errorText(e: unknown): string {
  return e instanceof Error ? e.message : String(e);
}
