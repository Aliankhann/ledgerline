# 0008 USD amounts convert at the Bank of Canada trade-date rate

**Status:** Draft · **Date:** 2026-11-25 · **Drafted by:** agent (dev, story L-25)

## Context

CRA wants amounts in CAD at the rate in effect on the day of each transaction. Before L-25 the engine treated USD
cents as CAD cents (B-036), so USD sales were left out of the tax year totals. `calc/fx/FxRateCache` (L-08) already
selects a rate and rounds once; nothing used it.

## Decision (built, for the owner to confirm or rewrite)

- Rate: Bank of Canada `FXUSDCAD` on the trade date (`effectiveDate`), else the last earlier published day (L-08).
- `AcbEngine` takes an optional `FxRateCache`. A USD buy converts amount plus commission with one rounding. A USD sell
  converts proceeds and commission separately, each at the sell's own date. Return of capital converts its amount.
- A USD activity with no rate (before or after the files, or no cache) is rejected `FX-RATE-MISSING` and shown in
  Data checks. The pool is unchanged. Nothing is guessed.
- Rates are a Bank of Canada Valet file committed at `app/src/main/resources/fx/` (2023-01-01 to 2025-12-31), loaded
  once at startup. The app never fetches live. Later years need a new committed file.
- `FX-HISTORICAL` explains a book value equal to the whole USD cost at one published rate that no buy used.

## Alternatives not taken

- Convert at settlement date, or at the year-end rate: not what the owner chose on 2026-11-25.
- Fetch rates live: breaks replay determinism.

## Consequences

- Replay stays deterministic: the same files give the same pools on any machine.
- The rounding mode is still the `ROUND-MODE` placeholder (L-25 owner decisions).
- Expected values in tests are arithmetic from the fixture rate, not CRA-published amounts (L-08 AC10 stays blocked).
