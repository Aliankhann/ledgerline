# L-26 Explain a difference caused by commissions the broker left out of book value

**Status:** Draft (owner to approve) · **Round:** 0

## Story
As an investor comparing my broker's book value with my ACB, I want a difference that comes from purchase commissions
to be labelled as such, with the buys involved, so I can see why my ACB is higher than the broker's figure.

## Why it matters
The owner's mockup lists "Missing acquisition commission" as one of three adjustments. Under ITA s.53/54 the cost of a
share includes outlays to acquire it (CRA "Calculating and reporting your capital gains and losses", worked example
adds the commission to ACB — the CRA-XYZ fixture already used by L-06/L-18). Brokers sometimes report book value
without some commissions.

## Acceptance criteria
1. **AC1 Commission-only difference.** A holding whose buys carry commissions C1..Cn (CAD, or converted per L-25) and
   whose broker book value equals ACB − (C1+…+Cn) gets one reason `COMMISSION-NOT-IN-BOOK` with amount = that sum and
   the buy entry ids; status EXPLAINED, footer "Difference fully explained by 1 adjustment(s)".
2. **AC2 Combined with other reasons.** When a superficial-loss add-back and/or `FX-HISTORICAL` are also present, the
   commission reason is attributed only if the remaining unexplained amount equals the commission sum (or a subset sum
   of whole buys); reasons still sum exactly to the difference.
3. **AC3 No false attribution.** If no subset of buy commissions equals the remainder, no `COMMISSION-NOT-IN-BOOK`
   reason appears; the remainder stays UNEXPLAINED and the footer does not say fully explained.
4. **AC4 Sold shares.** Only commissions still inside the remaining pool's ACB count (for a partly sold holding, the
   pro-rated commission in the remaining ACB, computed with the engine's own rounding); the reason lists the buy ids.
5. **AC5 Deterministic.** Same input in any arrival order gives the same reasons in the same order.

## Out of scope
Selling commissions (they are outlays on the sale, L-18). Commission rebates.

## Owner decisions
- Subset search limit (proposed: all buys if ≤ 20, otherwise whole-holding sum only).
- Approval beyond the 14-story cap.

## Change notes

- Dev (round 1): `HoldingExplanation.commissionReason` runs after the superficial-loss and `FX-HISTORICAL` reasons.
  It replaces the whole-sum `COST-BUY-COMMISSION` reason and the UNEXPLAINED remainder by `COMMISSION-NOT-IN-BOOK` when
  the remainder equals the commission left in the pool ACB for a subset of whole buys (buys by entry id, subsets in
  bitmask order; limit 20 buys, else whole-holding only; `// OWNER-INPUT: L-26 subset limit`). Each buy's share is the
  engine's own ACB minus the ACB replayed with that buy's commission zeroed, so partial sales use the engine's
  rounding and no new rounding exists. No match leaves UNEXPLAINED. `BookValueComparison.money` became `internal`.
  `ui/src/DataChecks.tsx` not changed: its map covers data checks, not explain reasons.
  Also adds an `SL-ADDBACK-TARGET` reason (pool add-back total) when it fits the difference, since AC2 needs it and BookValueComparison left it UNEXPLAINED.
