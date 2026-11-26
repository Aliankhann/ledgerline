# L-27 Explain a difference made of several adjustments at once

**Status:** Draft (owner to approve) · **Round:** 0

## Story
As an investor whose broker book value differs from my ACB for more than one reason, I want each part of the
difference labelled separately (FX, superficial loss, commission), so that "fully explained" means every cent is
accounted for, as in the owner's mockup ("Difference fully explained by 3 adjustments").

## Why it matters
Today `FX-HISTORICAL` (L-25) takes the whole difference whenever it fires, so a holding cannot show FX plus a
superficial-loss add-back plus a missing commission. L-26 AC2 already expects the commission reason to combine with
`FX-HISTORICAL` and SL reasons.

## Acceptance criteria
1. **AC1 FX is a part, not the whole.** `FX-HISTORICAL` carries only the amount explained by the rate difference:
   ACB at the trade-date rates minus the same holding's cost at the single rate the broker appears to have used
   (the L-25 detector's rate), computed by the engine's own replay. The remainder is passed on.
2. **AC2 Remainder attribution.** After FX, the superficial-loss add-back reason and `COMMISSION-NOT-IN-BOOK` (L-26
   rules) are tried on the remainder, in that order; whatever is left is UNEXPLAINED.
3. **AC3 Three adjustments.** A USD holding with an SL add-back, buy commissions the broker left out, and a broker book
   value at a single rate shows exactly three reasons whose amounts sum to the difference, status EXPLAINED, footer
   "Difference fully explained by 3 adjustments".
4. **AC4 Existing behaviour.** A holding whose whole difference is FX still shows one `FX-HISTORICAL` reason with the
   whole amount (L-25 AC6); L-17 and L-26 tests stay green.
5. **AC5 No false attribution.** If the remainder matches nothing, it stays UNEXPLAINED and the footer is not shown.

## Out of scope
New reason types. Choosing among several possible single rates beyond what L-25 already tries.

## Owner decisions
- Order of attribution after FX (proposed: SL add-back, then commission).
- Approval beyond the 14-story cap.

## Change notes

- `HoldingExplanation.historicalFx` now finds the single rate by replaying the holding with `AcbEngine(rules, fx, fixedRate)`
  (new optional `fixedRate`, USD only) and carries only ACB at trade-date rates minus that single-rate cost (plain
  replays, with commissions first, else without). No new rounding: every amount is a difference of engine cents.
- `commissionReason` then tries the SL add-back on what FX left, then the commission subset on the rest; the leftover is
  UNEXPLAINED. The SL reason's `entryIds` now also name the buys the pool took in after the sale (the replay's
  trigger there is the earlier buy), so the reason points at the holding the add-back sits on.
- Limit: if a commission sits on a buy that feeds a superficial-loss sale, removing it changes the denied loss too,
  so the subset check fails and the remainder stays UNEXPLAINED (honest, not forced). The demo data avoids this.
- Demo: `docs/demo/sample-trades.csv` gains SYN-DEMO (USD) rows, `demo/book-values.csv` its 8420.00 book value, and
  `scripts/demo.sh` checks the explain output. Compose mounts `demo/book-values.csv`, so the route sees it.
