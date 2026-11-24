import { useEffect, useState } from "react";
import { errorText, fetchJson, fetchYear } from "./api";
import type { ApiRefusal, BookView, YearView } from "./api";

export type TaxpayerData = {
  book: BookView | null;
  bookError: string | null;
  years: number[];
  year: number | null;
  setYear: (y: number) => void;
  yearView: YearView | null;
  refusal: ApiRefusal | null;
  yearError: string | null;
};

/** Everything the summary bar and the tabs read for one taxpayer; reloads when the taxpayer or year changes. */
export function useTaxpayerData(taxpayer: string): TaxpayerData {
  const [book, setBook] = useState<BookView | null>(null);
  const [bookError, setBookError] = useState<string | null>(null);
  const [years, setYears] = useState<number[]>([]);
  const [year, setYear] = useState<number | null>(null);
  const [yearView, setYearView] = useState<YearView | null>(null);
  const [refusal, setRefusal] = useState<ApiRefusal | null>(null);
  const [yearError, setYearError] = useState<string | null>(null);

  useEffect(() => {
    let live = true;
    setBook(null);
    setBookError(null);
    setYears([]);
    setYear(null);
    setYearView(null);
    setRefusal(null);
    setYearError(null);
    if (taxpayer.trim() === "") return;
    const tp = encodeURIComponent(taxpayer);
    fetchJson<BookView>(`/api/v1/holdings/${tp}/acb-vs-book`)
      .then((v) => live && setBook(v))
      .catch((e: unknown) => live && setBookError(errorText(e)));
    fetchJson<{ years: number[] }>(`/api/v1/tax-year/${tp}`)
      .then((r) => {
        if (!live) return;
        setYears(r.years);
        setYear(r.years[0] ?? null);
      })
      .catch((e: unknown) => live && setYearError(errorText(e)));
    return () => {
      live = false;
    };
  }, [taxpayer]);

  useEffect(() => {
    if (year === null) return;
    let live = true;
    setYearView(null);
    setRefusal(null);
    setYearError(null);
    fetchYear(taxpayer, year)
      .then((res) => {
        if (!live) return;
        setYearView(res.view);
        setRefusal(res.refusal);
      })
      .catch((e: unknown) => live && setYearError(errorText(e)));
    return () => {
      live = false;
    };
  }, [taxpayer, year]);

  return { book, bookError, years, year, setYear, yearView, refusal, yearError };
}
