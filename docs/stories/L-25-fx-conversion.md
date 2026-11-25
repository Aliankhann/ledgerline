# L-25 Convert USD trades to CAD at the Bank of Canada trade-date rate

**Status:** Draft (owner decided the rate rule on 2026-11-25; story approval pending) · **Round:** 0

## Story
As a Canadian investor with US-dollar trades, I want every USD buy, sell and commission converted to CAD at the Bank of
Canada rate for its trade date, so that my ACB, gains and tax-year totals are in CAD and include my US holdings.

## Why it matters
B-036: USD amounts were shown as CAD and are now excluded from totals. CRA requires amounts in CAD at the rate in effect
on the day of the transaction (see `docs/stories/L-08-usd-cad-fx.md` › Background, and `docs/problem/evidence.md`).
The owner's mockup lists a "Historical FX correction" reason; that needs conversion first.

## Owner decision (2026-11-25)
Rate = Bank of Canada `FXUSDCAD` daily rate on the **trade date** (`effective_date`); on a day with no published rate,
the last earlier published rate (the L-08 selection rule, already tested by L-08 AC1–AC9). Rates come from Bank of
Canada Valet files committed to the repo; the app never fetches live. ADR 0008 (draft) records this.

## Acceptance criteria
Converted amounts use `FxRateCache.toCadCents` (one rounding through the rule set's reporting rounding). No CRA-published
converted amount exists in the repo (L-08 AC10 stays blocked), so the expected values below are **arithmetic from the
fixture rate**, not tax claims.

1. **AC1 USD buy converts at its trade-date rate.** A USD BUY of amount A cents and commission C cents on date D enters
   the pool with cost `toCadCents(A + C, rateFor(D))`; the explain step shows the USD amount, the rate, the rate date and
   the CAD amount.
2. **AC2 USD sell converts at its own trade-date rate.** Proceeds and outlays of a USD SELL use the rate for the sell's
   trade date, not the buy's. The gain equals CAD proceeds − CAD outlays − ACB of shares sold.
3. **AC3 Weekend or holiday trade** uses the last earlier published rate; the explain step names the rate date.
4. **AC4 Missing rate is a rejection, not a guess.** A USD trade dated before the earliest rate (or after the latest) in
   the committed files is rejected with reason `FX-RATE-MISSING` and appears in Data checks; no pool changes.
5. **AC5 Tax year includes USD sales.** The L-18 `FX-NOT-CONVERTED` exclusion is removed: converted USD rows appear with
   currency CAD (and the original USD amount and rate in notes), and count in every total. The CSV marks no row
   NOT-CONVERTED. (QE updates the B-036 tests to the new behaviour.)
6. **AC6 Historical FX reason in the explanation.** For a USD holding, if the broker book value equals the holding's cost
   converted at a single rate other than the trade-date rates (e.g. one rate applied to all buys), the difference is
   attributed to reason `FX-HISTORICAL` with the buy entry ids, and counts toward "fully explained" like any other reason.
7. **AC7 Determinism and invariants.** Replay, reconcile and order-independence properties hold with USD activities;
   CAD activities never look up a rate (L-08 AC12).

## Out of scope
Currencies other than USD; FX gain/loss on cash balances; live fetching; T1135 foreign property.

## Owner decisions
- Rounding mode of the conversion (uses the existing `ROUND-MODE` placeholder).
- Whether `FX-HISTORICAL` should also detect a book value at "today's" rate.
- Approval beyond the 14-story cap.

## Change notes
