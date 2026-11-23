# L-16 Missing-data warnings on the ACB-vs-book list

**Status:** Draft (provisional, owner to approve; OWNER-INPUT)  <!-- Only the owner sets Approved. -->
**Day:** N · **Branch:** `l-16` · **Round:** 0 / 3

## Story

As a _self-directed investor_, I want a holding flagged when the file's history cannot support its ACB, so that I do
not trust a number that is missing shares or a cost.

## Why it matters

The T5008 box 20 "may or may not reflect your ACB" (E-36) and Wealthsimple says transferred-in assets "may not have an
accurate ACB" (E-37); see [`docs/problem/evidence.md`](../problem/evidence.md). The list should say when it is
guessing. This story involves no tax values: warnings carry `amountMinor` 0 and no expected value needs a source.
Extra story beyond the 14-story cap; owner approval needed.

## Acceptance criteria

Rows come from `BookValueComparison.compareAll`, the route in `AcbVsBookRoutes.kt` and the table in `ui/src/AcbVsBook.tsx`.
A warning is a `Reason` whose `ruleId` is the sub-rule id itself (`DATA-GAP-TRANSFER-NO-COST`, `DATA-GAP-OVERSELL`;
OWNER-INPUT: placeholder names), `amountMinor = 0`, `entryIds` = the ledger entries involved. Provisional choice
(2026-11-23) so `Reason` keeps its shape; the existing `DATA_GAP` id stays for a book value with no ledger activity.

1. **AC1** Share-in with no cost
   - Input: taxpayer T, security XYZ, non-registered. Activities: entry 1 `TRANSFER_IN` 100 XYZ; entry 2 `BUY` 10 XYZ
     for $100.00; broker book value for XYZ $1,100.00.
   - Expected: the XYZ row has a reason `DATA_GAP` / `DATA-GAP-TRANSFER-NO-COST`, `entryIds = [1]`, `amountMinor = 0`,
     text "shares arrived with no cost basis in the file". Other reasons, if any, are unchanged.
2. **AC2** Sale exceeds shares held
   - Input: entry 1 `SELL` 5 XYZ (no earlier buy); entry 2 `BUY` 10 XYZ for $100.00.
   - Expected: the XYZ row has `DATA_GAP` / `DATA-GAP-OVERSELL`, `entryIds = [1]`, `amountMinor = 0`, text "a sale
     exceeds the shares the file shows as held". The pool is unchanged by the sell (existing rejection behaviour).
3. **AC3** Over-sell after a buy
   - Input: entry 1 `BUY` 10 XYZ; entry 2 `SELL` 12 XYZ.
   - Expected: `DATA-GAP-OVERSELL` with `entryIds = [2]`; a sell of 10 instead gives no `DATA_GAP` reason.
4. **AC4** Clean history has no warning
   - Input: entry 1 `BUY` 10 XYZ; entry 2 `SELL` 4 XYZ.
   - Expected: no reason with sub-rule `DATA-GAP-*`.
5. **AC5** Book value with no ledger activity (existing, L-11/C31)
   - Input: book value for ABC $50.00, no activities.
   - Expected: unchanged; reason `DATA_GAP`, `entryIds = []`, `amountMinor` = difference. Referenced, not re-specified.
6. **AC6** Warnings coexist with explained differences
   - Input: AC1 input plus a return of capital that explains part of the difference.
   - Expected: both the rule reason and the warning appear; the amounts of reasons that carry cents still sum to the
     difference (warnings contribute 0). Warnings also appear on a row with no book value (`NO_BROKER_VALUE`).
7. **AC7** Route and UI show it
   - Input: the AC1 data through `GET` on the acb-vs-book route.
   - Expected: JSON `reasons` contains the warning with its entry ids; the UI row lists it in the reasons cell.

## Out of scope

- Deciding a cost for transfers, or whether `TRANSFER_IN` enters the pool (C33, owner). Pool rules do not change.
- Registered-account shares (not in the pool by design, `POOL-REGISTERED-EXCL`).
- Fixing or re-ordering rejected sells; they stay rejected, never clipped (invariant 7).

## Needs owner decision

- **OWNER-INPUT:** sub-rule ids `DATA-GAP-TRANSFER-NO-COST` and `DATA-GAP-OVERSELL` are placeholders; they must also
  be added to `CaIta2026v1.ruleIds` if the L-11 tests still require that (see status.md, L-11 round 2).
- **OWNER-INPUT:** `compareAll(pools, bookValues)` cannot see these cases today (see change notes). Dev needs a third
  input (transfers and rejections with entry ids). Owner to accept the signature change.
- C33 (cost of transfers) stays open; this story only warns. No tax judgment is made here.

## Test map (QE fills)

| AC | Test |
| --- | --- |
| AC1 | `tests/spec/.../L16Spec.kt` › "L-16 AC1: ..." |

## Change notes (Dev fills)

- BA code facts for Dev: `Replay.kt:16,47` drops activities outside BUY/SELL/ROC before the engine, so `TRANSFER_IN`
  never reaches a pool or its history. `AcbEngine.kt:12-27` rejects an over-sell; `Replay.kt:59-62` records a
  `Rejection` that has only `idempotencyKey` (`AcbPool.kt:52-58`), no entry id, so map key to `PostedActivity.entryId`.
