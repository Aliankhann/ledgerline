# L-08 USD to CAD by trade date

**Status:** Draft  <!-- Draft → Approved → Tests written → In dev → In QE → Done. Only the owner sets Approved. -->
**Day:** 7 · **Branch:** `L-08-usd-cad-fx` · **Round:** 0 / 3

## Story

As a self-directed investor, I want every USD amount converted to CAD with the Bank of Canada daily USD/CAD rate for
the trade date, or the prior business day's rate when no rate is published that day, so that my ACB and gains are in
CAD the way the tax rules expect, and the same trades always convert the same way, even offline.

## Why it matters

USD holdings make broker book value and true ACB diverge further (`docs/build-plan.md` › Scope). **The evidence entry
is not written yet:** [`docs/problem/evidence.md`](../problem/evidence.md) is still empty as of 2026-11-19.

## Background: the rate source (unverified, re-check before approval)

**Second sourcing attempt (2026-11-19): nothing fetched, nothing changed.** `https://www.bankofcanada.ca/valet/docs`
failed twice with `getaddrinfo ENOTFOUND` (DNS), and `www.canada.ca` failed the same way. The fetch tool said the
hosts are outside the session's allowed network domains. So the Valet series, endpoint, JSON shape, the real
`FXUSDCAD` observations for the four requested ranges, the missing-date list and the 2017 single-rate change are all
still unconfirmed, and the fixture below is still **synthetic**. No raw JSON was saved (`tests/` is QE-owned anyway).
Rerun this task once `www.bankofcanada.ca` resolves from the session.

`www.bankofcanada.ca` was **not opened** in the BA session: two other hosts failed DNS and the session rules say to
stop trying new hosts. Everything below comes from search results and must be confirmed against the official pages
before this story is approved:

- **Series:** `FXUSDCAD`, described in third-party material as the daily average USD/CAD rate.
- **Endpoint (unverified):** `GET https://www.bankofcanada.ca/valet/observations/FXUSDCAD/json?start_date=YYYY-MM-DD&end_date=YYYY-MM-DD`,
  keyless. Official docs are said to live at https://www.bankofcanada.ca/valet/docs (not opened). Third-party
  description of the endpoint pattern: https://jentic.com/apis/bankofcanada.ca/bankofcanada.md (not opened). The JSON
  field names (observation date and value) and the number of decimal places are **not confirmed**.
- **Single daily rate (unverified):** search summaries of Bank of Canada notices say the new single indicative daily
  rate (a daily average, published once a day by 16:30 ET) began on **2017-03-01**. Noon and closing rates ran in
  parallel until **2017-04-28**, and only the new rates were published from **2017-05-01**. Pages to open:
  - https://www.bankofcanada.ca/2016/02/bank-canada-announces-future-changes-published-foreign-exchange-rate-data/
  - https://www.bankofcanada.ca/2016/12/bank-canada-announces-details-forthcoming-changes/
  - https://www.bankofcanada.ca/2017/03/bank-canada-begins-publishing-new-foreign-exchange/
  - https://www.bankofcanada.ca/2021/11/background-information-on-foreign-exchange-rates/
- **CRA rule on which rate to use:** not opened. Candidate: CRA Guide T4037,
  https://www.canada.ca/en/revenue-agency/services/forms-publications/publications/t4037/capital-gains.html. Whether CRA
  states a "prior business day" fallback is **unknown**; the fallback is currently a project rule from
  `docs/build-plan.md`, not a sourced tax rule.

## Fixture

**All rates below are SYNTHETIC.** They are not real Bank of Canada rates and were not fetched. They are deliberately
patterned (`1.MMDD` for 2025 dates, `2.MMDD` for 2026) so nobody mistakes them for real data or picks the wrong row
by accident. They are written to 4 decimal places as a placeholder format; the real Valet precision is unconfirmed.

Fixture file `fx/FXUSDCAD.synthetic.json` (name to be confirmed by QE), a local cache that is used with no network
access:

| Date | Weekday | Synthetic rate |
| --- | --- | --- |
| 2025-03-03 | Mon | 1.0303 |
| 2025-03-06 | Thu | 1.0306 |
| 2025-03-07 | Fri | 1.0307 |
| 2025-03-10 | Mon | 1.0310 |
| 2025-04-16 | Wed | 1.0416 |
| 2025-04-17 | Thu | 1.0417 |
| 2025-06-27 | Fri | 1.0627 |
| 2025-06-30 | Mon | 1.0630 |
| 2025-07-02 | Wed | 1.0702 |
| 2025-10-09 | Thu | 1.1009 |
| 2025-10-10 | Fri | 1.1010 |
| 2025-10-14 | Tue | 1.1014 |
| 2025-12-23 | Tue | 1.1223 |
| 2025-12-24 | Wed | 1.1224 |
| 2025-12-29 | Mon | 1.1229 |
| 2025-12-30 | Tue | 1.1230 |
| 2025-12-31 | Wed | 1.1231 |
| 2026-01-02 | Fri | 2.0102 |

Dates missing from the table are, by construction, days with no published rate. The holidays named in the ACs are
real Canadian statutory holidays on those 2025/2026 dates. **Whether the Bank of Canada actually skipped publication
on each one has not been checked;** the owner can verify by fetching Valet for those ranges. The tests depend only on
the fixture, not on the real calendar. Easter Monday (2025-04-21) is deliberately left out of the fixture and of
every AC, because Bank of Canada publication on that day is unconfirmed.

## Acceptance criteria

The expected output of each rate-selection AC is **the date and rate that were used**, not a converted CAD amount.
Converting amounts depends on the rounding decision below and on a sourced CRA example (AC10).

1. **AC1 Business day uses its own rate.** Given the fixture, when a USD trade has trade date 2025-03-06 (Thu), then
   the rate used is 2025-03-06 = 1.0306.
2. **AC2 Saturday and Sunday use Friday.** Given the fixture, when USD trades have trade dates 2025-03-08 (Sat) and
   2025-03-09 (Sun), then both use 2025-03-07 = 1.0307.
3. **AC3 First business day after a weekend uses its own rate.** Trade date 2025-03-10 (Mon) uses 2025-03-10 =
   1.0310, not Friday's 1.0307.
4. **AC4 Long weekend, statutory holiday Monday (Thanksgiving, Mon 2025-10-13).** Trade dates 2025-10-11 (Sat),
   2025-10-12 (Sun) and 2025-10-13 (Mon, Thanksgiving) all use 2025-10-10 (Fri) = 1.1010.
5. **AC5 First business day after the long weekend.** Trade date 2025-10-14 (Tue) uses 2025-10-14 = 1.1014.
6. **AC6 Mid-week holiday (Canada Day, Tue 2025-07-01).** Trade date 2025-07-01 uses 2025-06-30 (Mon) = 1.0630, and
   trade date 2025-07-02 (Wed) uses 1.0702.
7. **AC7 Holiday Friday plus weekend (Good Friday, Fri 2025-04-18).** Trade dates 2025-04-18 (Fri, Good Friday),
   2025-04-19 (Sat) and 2025-04-20 (Sun) all use 2025-04-17 (Thu) = 1.0417.
8. **AC8 Four-day gap (Christmas Day Thu 2025-12-25, Boxing Day Fri 2025-12-26).** Trade dates 2025-12-25,
   2025-12-26, 2025-12-27 (Sat) and 2025-12-28 (Sun) all use 2025-12-24 (Wed) = 1.1224. Trade date 2025-12-29 (Mon)
   uses 1.1229.
9. **AC9 Across a year boundary (New Year's Day, Thu 2026-01-01).** Trade date 2026-01-01 uses 2025-12-31 (Wed) =
   1.1231. Trade date 2026-01-02 (Fri) uses 2.0102.
10. **AC10 Converted CAD amount.** Given a USD trade amount and the selected rate, the CAD amount equals the documented
    conversion rule.
    - Input / Expected: **NEEDS SOURCED EXAMPLE** (a published CRA or other reputable worked example converting a
      USD trade to CAD), and blocked on the rounding decision. No numbers are given here, so the BA has not computed
      a value.
11. **AC11 (non-tax) No rate on or before the trade date is a rejection.** Given the fixture, whose earliest date is
    2025-03-03, when a USD trade has trade date 2025-03-01 (Sat), then the activity is rejected with a reason naming
    the series `FXUSDCAD` and the date 2025-03-01. A later rate (2025-03-03) is never used, and no ledger entry is
    posted.
12. **AC12 (non-tax) CAD activities skip FX.** Given a CAD buy of 10000 cents on 2025-03-08 (Sat), then no rate is
    looked up and the CAD amount stays 10000 cents.
13. **AC13 (non-tax) Offline and deterministic.** Given the fixture as the only rate source and the network disabled,
    when AC1–AC9 run twice, then every selection is identical and no network call is attempted.
14. **AC14 (non-tax) Rate is never floating point.** Given the fixture string `1.0307`, then the parsed rate is an
    integer fixed-point value. At a placeholder scale of 10^4 that is the integer `10307`; the final scale is an owner
    decision. No `Double`, `Float` or `BigDecimal` arithmetic is used (invariant 8).

## Out of scope

- Currencies other than USD and CAD; FX conversion activities' own gain/loss on holding USD cash.
- Fetching live rates during tests or the demo (the fetch-and-cache tool is a separate task).
- Trades before 2017-03-01 (pre-single-rate era), pending the owner decision below.
- Intraday or broker-supplied rates.

## Needs owner decision

Tax judgment and design (each goes into an ADR with its source once decided):

- **Trade date vs settlement date** for choosing the rate. The story assumes trade date, per `docs/build-plan.md`.
  CRA guidance has not been opened.
- **Prior business day rule is a project rule, not yet sourced.** Accept it as a documented simplification, or require
  a CRA source first? CRA may also allow other "acceptable" rates; unverified.
- **Definition of "business day":** a day is a business day if a rate exists in the cache (recommended: no separate
  holiday calendar to maintain), or use a holiday calendar that must agree with the cache?
- **Maximum lookback / staleness:** after how many calendar days with no rate does the engine reject, rather than use
  an old rate (for example a cache gap)?
- **Pre-2017 trades:** reject, or use the legacy noon rate series? This affects whether the synthetic generator may
  emit pre-2017 dates.
- **Rounding:** precision of the stored rate (scale), when the converted CAD amount is rounded (per trade, per line,
  or only at reporting), and which rule (half-up, half-even). This must match L-06's rule.
- **Commissions in USD:** converted at the same trade-date rate as the trade (assumed), or separately?
- **ROC and dividends in USD:** converted at the payment date or the record date rate?

## Test map (QE fills)

| AC | Test |
| --- | --- |
| AC1 | `tests/spec/.../L08Spec.kt` › "L-08 AC1: …" |

## Change notes (Dev fills)

- …
