# L-08 USD to CAD by trade date

**Status:** Tests written (provisional)  <!-- Draft → Approved → Tests written → In dev → In QE → Done. Only the owner sets Approved. -->
**Day:** 7 · **Branch:** `L-08-usd-cad-fx` · **Round:** 0 / 3

## Story

As a self-directed investor, I want every USD amount converted to CAD with the Bank of Canada daily USD/CAD rate for
the trade date, or the prior business day's rate when no rate is published that day, so that my ACB and gains are in
CAD the way the tax rules expect, and the same trades always convert the same way, even offline.

## Why it matters

USD holdings make broker book value and true ACB diverge further (`docs/build-plan.md` › Scope).
[`docs/problem/evidence.md`](../problem/evidence.md) E-04 records, from the `tsiemens/acb` README, that USD holdings need
a daily Bank of Canada rate. No evidence entry yet quantifies how often this matters.

## Background: the rate source (observed 2026-11-19)

**Status (2026-11-19, third pass): the rate data is now real and observed; the tax rule for choosing a rate is still
not sourced from CRA.** The orchestrator fetched the Bank of Canada Valet API with `curl` (HTTP 200) and saved the raw
JSON; the BA read those files. The Valet documentation page (`https://www.bankofcanada.ca/valet/docs`) came back with no
usable text (it is rendered by script), so nothing is taken from it. CRA T4037 was not opened.

- **Series:** `FXUSDCAD`. Each response says: label "USD/CAD", description "Daily average exchange rate of the US
  dollar in Canadian dollars.", frequency "Business daily", unit "Canadian dollars per US dollar".
- **Endpoint:** `GET https://www.bankofcanada.ca/valet/observations/FXUSDCAD/json?start_date=YYYY-MM-DD&end_date=YYYY-MM-DD`,
  keyless. Response shape: `{"terms":…, "seriesDetail":{…}, "observations":[{"d":"2025-03-03","FXUSDCAD":{"v":"1.4428"}}, …]}`.
  The date is the field `d`; the rate is the **string** `v`. All 32 observed values have exactly four decimal places
  (trailing zeros kept, for example `"1.4000"`).
- **Missing dates are simply absent from `observations`.** There is no row with a null or a flag. The ranges below list
  which dates are absent.
- **Single daily rate (still unverified):** search summaries said the single daily indicative rate began on
  2017-03-01, with noon and closing rates ending 2017-04-28. The notice pages were not fetched, so this is unconfirmed
  here. `evidence.md` E-04 (from the `tsiemens/acb` README, not a Bank of Canada page) says that tool uses historical noon
  rates for 2016 and earlier and indicative rates for 2017 and later.
- **CRA rule on which rate to use:** not opened. Second-hand only: mycostbase.ca,
  https://mycostbase.ca/blog/broker-book-value-vs-adjusted-cost-base-canada/, section "A note about foreign-currency
  holdings": "Canadian capital gains and losses are reported in Canadian dollars. Purchase cost and sale proceeds
  should be converted separately using the relevant rate for each transaction. CRA's foreign-currency folio
  identifies the Bank of Canada rate as an accepted source. It also says another independent, market-recognized and
  verifiable source may be accepted when it is used consistently and meets CRA's stated conditions." ActiveACB,
  https://activeacb.ca/book-cost-vs-acb.html, table row "US-dollar securities": "Each buy and sell converted at the
  Bank of Canada rate for its own date, with the pool kept in CAD". Neither says what to do on a day with no published
  rate. **No page read supports the "prior business day" fallback; it is a project rule from `docs/build-plan.md`.**
  The data below shows the Bank of Canada publishes nothing on weekends and some holidays, so a fallback is needed.

## Fixture

The fixture is the raw Valet response for five ranges, fetched 2026-11-19. QE owns `tests/`; the BA cannot place the
files. Raw copies are in the orchestrator's session scratch directory
(`/tmp/claude-0/-home-user-ledgerline/1baa4982-446d-5668-88b5-1ca5169cae94/scratchpad/fx/`), which is not durable. **QE or
the owner must re-fetch each URL and diff it against the table before committing the fixture**, for example as
`fx/FXUSDCAD.boc-2025.json` (name to be confirmed by QE). The table below was transcribed from those files; the
re-fetch is the check on the transcription.

Source URLs (one per range, exactly as fetched):

| Range | URL |
| --- | --- |
| R1 | https://www.bankofcanada.ca/valet/observations/FXUSDCAD/json?start_date=2025-03-03&end_date=2025-03-14 |
| R2 | https://www.bankofcanada.ca/valet/observations/FXUSDCAD/json?start_date=2025-04-15&end_date=2025-04-22 |
| R3 | https://www.bankofcanada.ca/valet/observations/FXUSDCAD/json?start_date=2025-06-27&end_date=2025-07-03 |
| R4 | https://www.bankofcanada.ca/valet/observations/FXUSDCAD/json?start_date=2025-10-08&end_date=2025-10-15 |
| R5 | https://www.bankofcanada.ca/valet/observations/FXUSDCAD/json?start_date=2025-12-22&end_date=2026-01-05 |

Observations (32 rows). "Fixed-point" is the value at the proposed scale of 10⁻⁴ (owner decision below).

| Range | Date | Weekday | Rate (`v`) | Fixed-point |
| --- | --- | --- | --- | --- |
| R1 | 2025-03-03 | Mon | 1.4428 | 14428 |
| R1 | 2025-03-04 | Tue | 1.4489 | 14489 |
| R1 | 2025-03-05 | Wed | 1.4370 | 14370 |
| R1 | 2025-03-06 | Thu | 1.4309 | 14309 |
| R1 | 2025-03-07 | Fri | 1.4371 | 14371 |
| R1 | 2025-03-10 | Mon | 1.4431 | 14431 |
| R1 | 2025-03-11 | Tue | 1.4451 | 14451 |
| R1 | 2025-03-12 | Wed | 1.4391 | 14391 |
| R1 | 2025-03-13 | Thu | 1.4409 | 14409 |
| R1 | 2025-03-14 | Fri | 1.4388 | 14388 |
| R2 | 2025-04-15 | Tue | 1.3934 | 13934 |
| R2 | 2025-04-16 | Wed | 1.3890 | 13890 |
| R2 | 2025-04-17 | Thu | 1.3857 | 13857 |
| R2 | 2025-04-21 | Mon | 1.3820 | 13820 |
| R2 | 2025-04-22 | Tue | 1.3827 | 13827 |
| R3 | 2025-06-27 | Fri | 1.3676 | 13676 |
| R3 | 2025-06-30 | Mon | 1.3643 | 13643 |
| R3 | 2025-07-02 | Wed | 1.3622 | 13622 |
| R3 | 2025-07-03 | Thu | 1.3575 | 13575 |
| R4 | 2025-10-08 | Wed | 1.3952 | 13952 |
| R4 | 2025-10-09 | Thu | 1.4000 | 14000 |
| R4 | 2025-10-10 | Fri | 1.4000 | 14000 |
| R4 | 2025-10-14 | Tue | 1.4046 | 14046 |
| R4 | 2025-10-15 | Wed | 1.4045 | 14045 |
| R5 | 2025-12-22 | Mon | 1.3748 | 13748 |
| R5 | 2025-12-23 | Tue | 1.3705 | 13705 |
| R5 | 2025-12-24 | Wed | 1.3674 | 13674 |
| R5 | 2025-12-29 | Mon | 1.3686 | 13686 |
| R5 | 2025-12-30 | Tue | 1.3693 | 13693 |
| R5 | 2025-12-31 | Wed | 1.3706 | 13706 |
| R5 | 2026-01-02 | Fri | 1.3737 | 13737 |
| R5 | 2026-01-05 | Mon | 1.3768 | 13768 |

**Dates inside each requested range with no observation** (verified against the saved responses). The holiday names
are the BA's general knowledge of the 2025 and 2026 Canadian calendar; the response contains only the absence, not a
reason.

| Range | Absent date | Weekday | Probable reason |
| --- | --- | --- | --- |
| R1 | 2025-03-08, 2025-03-09 | Sat, Sun | weekend |
| R2 | 2025-04-18 | Fri | Good Friday |
| R2 | 2025-04-19, 2025-04-20 | Sat, Sun | weekend |
| R3 | 2025-06-28, 2025-06-29 | Sat, Sun | weekend |
| R3 | 2025-07-01 | Tue | Canada Day |
| R4 | 2025-10-11, 2025-10-12 | Sat, Sun | weekend |
| R4 | 2025-10-13 | Mon | Thanksgiving |
| R5 | 2025-12-25 | Thu | Christmas Day |
| R5 | 2025-12-26 | Fri | Boxing Day |
| R5 | 2025-12-27, 2025-12-28 | Sat, Sun | weekend |
| R5 | 2026-01-01 | Thu | New Year's Day |
| R5 | 2026-01-03, 2026-01-04 | Sat, Sun | weekend |

Three things in this data matter for the tests and the decisions:

1. **2025-04-21 (Easter Monday) has a rate (1.3820)** while 2025-04-18 (Good Friday) has none. A rule "a business day is a
   day with a rate in the cache" gets both right. A holiday calendar that treated Easter Monday as a holiday would
   disagree with the Bank of Canada on that date. This is evidence for the owner decision on the business-day definition.
2. **2025-10-09 and 2025-10-10 have the same rate (1.4000).** A test that checks only the rate for the Thanksgiving
   weekend cannot tell Thursday's row from Friday's. AC4 must assert the **date of the rate used**, not only its value.
3. **Longest gap with no rate in these ranges is 4 calendar days:** 2025-12-25 to 2025-12-28 all use the 2025-12-24 rate
   (a 4-day-old rate). Good Friday weekend and Thanksgiving weekend are 3 days. These five ranges say nothing about gaps
   outside them.

## Acceptance criteria

The expected output of each rate-selection AC is **the date and rate that were used**, not a converted CAD amount.
Converting amounts depends on the rounding decision below and on a sourced CRA example (AC10, still unsourced).
AC1 to AC9 use real Bank of Canada observations. The selection rule they test (use the prior published date) is a
project rule, not a tax rule, so these ACs are sourced for the data and unsourced as tax logic.

1. **AC1 Business day uses its own rate.** Given the fixture, when a USD trade has trade date 2025-03-06 (Thu), then
   the rate used is 2025-03-06 = 1.4309 (`14309`).
2. **AC2 Saturday and Sunday use Friday.** Given the fixture, when USD trades have trade dates 2025-03-08 (Sat) and
   2025-03-09 (Sun), then both use 2025-03-07 = 1.4371 (`14371`).
3. **AC3 First business day after a weekend uses its own rate.** Trade date 2025-03-10 (Mon) uses 2025-03-10 =
   1.4431 (`14431`), not Friday's 1.4371.
4. **AC4 Long weekend, statutory holiday Monday (Thanksgiving, Mon 2025-10-13).** Trade dates 2025-10-11 (Sat),
   2025-10-12 (Sun) and 2025-10-13 (Mon) all use 2025-10-10 (Fri) = 1.4000 (`14000`). Assert the rate **date**
   (2025-10-10); Thursday 2025-10-09 has the same value.
5. **AC5 First business day after the long weekend.** Trade date 2025-10-14 (Tue) uses 2025-10-14 = 1.4046 (`14046`).
6. **AC6 Mid-week holiday (Canada Day, Tue 2025-07-01).** Trade date 2025-07-01 uses 2025-06-30 (Mon) = 1.3643
   (`13643`), and trade date 2025-07-02 (Wed) uses 2025-07-02 = 1.3622 (`13622`).
7. **AC7 Holiday Friday plus weekend, then Easter Monday (Good Friday Fri 2025-04-18).** Trade dates 2025-04-18 (Fri),
   2025-04-19 (Sat) and 2025-04-20 (Sun) all use 2025-04-17 (Thu) = 1.3857 (`13857`). Trade date 2025-04-21 (Mon,
   Easter Monday) uses its **own** rate 2025-04-21 = 1.3820 (`13820`), because a rate exists in the cache for it.
8. **AC8 Four-day gap (Christmas Day Thu 2025-12-25, Boxing Day Fri 2025-12-26).** Trade dates 2025-12-25,
   2025-12-26, 2025-12-27 (Sat) and 2025-12-28 (Sun) all use 2025-12-24 (Wed) = 1.3674 (`13674`). Trade date
   2025-12-29 (Mon) uses 2025-12-29 = 1.3686 (`13686`).
9. **AC9 Across a year boundary (New Year's Day, Thu 2026-01-01).** Trade date 2026-01-01 uses 2025-12-31 (Wed) =
   1.3706 (`13706`). Trade date 2026-01-02 (Fri) uses 2026-01-02 = 1.3737 (`13737`). Trade dates 2026-01-03 (Sat) and
   2026-01-04 (Sun) use 2026-01-02 = 1.3737.
10. **AC10 Converted CAD amount.** Given a USD trade amount and the selected rate, the CAD amount equals the documented
    conversion rule.
    - Input / Expected: **NEEDS SOURCED EXAMPLE.** No worked example converting a USD trade at a stated rate was found,
      so the BA has computed no value. Pages checked: adjustedcostbase.ca `?p=258` (ROC; has "US$1.11 per unit is
      equivalent to CAD$1.36851 per unit based on the exchange rate reported by the Bank of Canada" but **does not state
      the rate or the rounding**; the implied rate is about 1.2329 and the page does not say whether it truncated or
      rounded, so it cannot serve as an example), `?p=438`,
      `?p=154`, the "How to Calculate ACB" post, `?p=4`, activeacb.ca and mycostbase.ca (rule statements only, quoted
      above). Not opened: CRA T4037, CRA foreign-currency folio, adjustedcostbase.ca "Calculating Adjusted Cost Base
      when Purchasing Foreign Currency Securities". Blocked on the rounding decision as well.
11. **AC11 (non-tax) No rate on or before the trade date is a rejection.** Given the fixture, whose earliest date is
    2025-03-03, when a USD trade has trade date 2025-03-01 (Sat), then the activity is rejected with a reason naming
    the series `FXUSDCAD` and the date 2025-03-01. A later rate (2025-03-03) is never used, and no ledger entry is
    posted.
12. **AC12 (non-tax) CAD activities skip FX.** Given a CAD buy of 10000 cents on 2025-03-08 (Sat), then no rate is
    looked up and the CAD amount stays 10000 cents.
13. **AC13 (non-tax) Offline and deterministic.** Given the fixture as the only rate source and the network disabled,
    when AC1–AC9 run twice, then every selection is identical and no network call is attempted.
14. **AC14 (non-tax) Rate is never floating point.** Given the fixture string `"1.4371"`, then the parsed rate is an
    integer fixed-point value. At the proposed scale of 10⁴ that is the integer `14371`; `"1.4000"` is `14000` (trailing
    zeros are kept, so `1.4` and `1.4000` must be equal). The scale is an owner decision. No `Double`, `Float` or
    `BigDecimal` arithmetic is used (invariant 8).

## Out of scope

- Currencies other than USD and CAD; FX conversion activities' own gain/loss on holding USD cash.
- Fetching live rates during tests or the demo (the fetch-and-cache tool is a separate task).
- Trades before 2017-03-01 (pre-single-rate era), pending the owner decision below.
- Intraday or broker-supplied rates.

## Needs owner decision

Tax judgment and design (each goes into an ADR with its source once decided):

- **Trade date vs settlement date** for choosing the rate. The story assumes trade date, per `docs/build-plan.md`.
  ACTIVEACB says "the Bank of Canada rate for its own date" and the L-06 source (ACB-HOWTO) says to use the settlement
  date for ACB. CRA guidance has not been opened.
- **Prior business day rule is a project rule, not sourced.** Accept it as a documented simplification, or require a CRA
  source first? Second-hand, mycostbase says CRA also accepts another independent, market-recognized, verifiable rate if
  used consistently; unverified.
- **Definition of "business day":** recommended: a day is a business day if **a rate exists in the cache**, with no
  separate holiday calendar to maintain. Evidence from the real data: 2025-04-18 (Good Friday) has no rate but
  2025-04-21 (Easter Monday) does, so the Bank of Canada's own publication days do not follow an obvious holiday list
  (see "Three things in this data"). A holiday calendar would have to match the Bank's calendar exactly or it will
  disagree with the cache on some dates. Evidence is five date ranges only.
- **Fixed-point scale of the rate:** proposed 10⁻⁴ (`1.4371` becomes `14371`), because all 32 observed values have exactly
  four decimals. Open question: does the engine store the rate at 10⁻⁴ and keep the conversion exact until reporting,
  or at a finer scale in case the Bank changes precision? A Long at 10⁻⁴ has ample headroom for any plausible rate.
- **Maximum lookback / staleness:** after how many calendar days with no rate does the engine reject, rather than use
  an old rate (for example a cache gap)? Observed in these ranges: at most 4 days (Christmas 2025). Any limit must be
  at least that; the data cannot say more.
- **Pre-2017 trades:** reject, or use the legacy noon rate series? This affects whether the synthetic generator may
  emit pre-2017 dates. The 2017 change itself is still unconfirmed against Bank of Canada pages.
- **Rounding:** when the converted CAD amount is rounded (per trade, per line, or only at reporting), and which rule
  (half-up, half-even). This must match L-06's rule. mycostbase says cost and proceeds are "converted separately using
  the relevant rate for each transaction".
- **Commissions in USD:** converted at the same trade-date rate as the trade (assumed), or separately? No page read
  says.
- **ROC and dividends in USD:** converted at the payment date or the record date rate? adjustedcostbase.ca `?p=258`,
  site author (2021-06-01): "I'm not sure whether it is better to use the exchange rate on the record date or the
  payment date, but I would suggest being consistent between these two transactions." and "If an actual currency
  exchange occurs ... I would suggest using the exchange rate that was applied." Not a CRA source.
- **Fixture custody:** the raw JSON is in a non-durable scratch folder. QE or the owner should re-fetch the five URLs and
  commit the responses under `tests/fixtures/`.

## Test map (QE fills)

Spec: `tests/spec/kotlin/dev/ledgerline/spec/L08FxSpec.kt` (API contract in its KDoc). Fixtures: `tests/fixtures/l08/`
(README cites URLs). Property: `tests/property/.../L08FxPropertyTest.kt`. Status is provisional: the story is still Draft
and the `[OD]` rules (FX-SCALE, FX-COVERAGE, FX-DAY) are not owner-accepted.

| AC | Test (name starts with) |
| --- | --- |
| AC1 | "L-08 AC1: ..." |
| AC2 | "L-08 AC2: ..." |
| AC3 | "L-08 AC3: ..." |
| AC4 | "L-08 AC4: ..." (asserts the rate date 2025-10-10) |
| AC5 | "L-08 AC5: ..." |
| AC6 | "L-08 AC6: ..." |
| AC7 | "L-08 AC7: ..." |
| AC8 | "L-08 AC8: ..." |
| AC9 | "L-08 AC9: ..." |
| AC10 | disabled `xtest` "L-08 AC10: ..." (NEEDS SOURCED EXAMPLE); a structural exactness test is not a tax value |
| AC11 | "L-08 AC11: ..." (3 tests; the "no ledger entry" half is not covered, needs the ledger) |
| AC12 | "L-08 AC12: ..." |
| AC13 | "L-08 AC13: ..." (determinism only; "no network call" not provable here) |
| AC14 | "L-08 AC14: ..." (2 tests) |
| FX-FALLBACK property | `L08FxPropertyTest` "L-08 [FX-FALLBACK]: ..." |

## Change notes (Dev fills)

- …
