# L-04 Corrections by reversal

**Status:** Draft  <!-- Draft → Approved → Tests written → In dev → In QE → Done. Only the owner sets Approved. -->
**Day:** 3 · **Branch:** `L-04-corrections-by-reversal` · **Round:** 0 / 3

## Story

As a self-directed investor, I want to correct a wrong activity by reversing it and posting the right one, so that my
history keeps both the mistake and the fix and my balances end up exactly as if only the right one had happened.

## Why it matters

Late and corrected broker data is normal, and the audit trail must survive corrections. See
[`docs/problem/evidence.md`](../problem/evidence.md) (no sourced evidence yet; see A-09): "Activity data gets corrected after the fact;
an append-only ledger needs corrections that keep the history".

## Notes for QE and Dev

- Invariants covered: **4 Reversal symmetry** (AC1, AC2, AC9), **3 Immutability** (AC3), **1 Balance** (AC1).
- Builds on L-03. The `DomainPlugin` interface is defined on day 3 (validate, apply, explain); a reversal must be
  something a plugin can apply, but plugin behaviour beyond that is not tested here.
- Starting state for every AC unless stated: `TP-0001-NR1` has a CAD cash balance of `1000000` from deposit
  `TP-0001-2025-0001` dated `2025-01-06`, and holds no shares.
- Quantities assume the 10⁻⁶ share scale (`100` shares = `100000000` units).
- Reversal requests carry their own idempotency key. Endpoint names below are proposals the Dev may refine; QE tests
  the names the owner approves.

## Acceptance criteria

1. **AC1 Apply then reverse returns every balance to its prior value.**
   - Input: post `TP-0001-2025-0002` (`BUY`, `2025-01-07`, `SYN-AAA`, `100000000` units, `CAD` `amountMinor=301250`,
     `commissionMinor=495`). Then reverse it with reversal key `REV-TP-0001-2025-0002`.
   - Expected: after the buy, CAD cash `698255` and `SYN-AAA` holding `100000000`. After the reversal, CAD cash
     `1000000` and `SYN-AAA` holding `0`, i.e. every account balance in every unit equals its value before the buy.
     The reversal entry is balanced per unit.
2. **AC2 The reversal mirrors the original line for line.**
   - Input: the entry pair from AC1.
   - Expected: the reversal has the same number of lines as the original; for each original line there is exactly
     one reversal line with the same account, same unit, same amount and the opposite side (debit ↔ credit).
3. **AC3 The original is untouched and linked, not edited.**
   - Input: SHA-256 of the original entry's rows taken before and after the reversal in AC1.
   - Expected: the hashes are equal. The reversal entry records the original's entry id. Querying the original's
     status returns `REVERSED` with the reversal's id, derived from the link, not from an updated column.
4. **AC4 Reversing an entry that does not exist is refused.**
   - Input: reverse entry id `00000000-0000-0000-0000-000000000000` (or the Dev's equivalent unknown id) with key
     `REV-UNKNOWN-1`. Proposed: `POST /v1/entries/{entryId}/reversal` → HTTP `404`, code `ENTRY_NOT_FOUND`.
   - Expected: error `ENTRY_NOT_FOUND`; ledger row counts unchanged.
5. **AC5 The same reversal request twice is a no-op.**
   - Input: repeat AC1's reversal with the same key `REV-TP-0001-2025-0002`.
   - Expected: success response that returns the existing reversal's id (proposed HTTP `200` instead of `201`); still
     exactly one reversal entry; balances as at the end of AC1.
6. **AC6 A second, different reversal of the same entry is refused.**
   - Input: after AC1, reverse the same original again with a new key `REV-TP-0001-2025-0002-B`.
   - Expected: error `ALREADY_REVERSED` (proposed HTTP `409`) naming the existing reversal id; no new rows.
7. **AC7 Reversing a reversal is refused (proposed default, needs owner decision).**
   - Input: after AC1, request a reversal of the reversal entry with key `REV-REV-1`.
   - Expected: error `CANNOT_REVERSE_REVERSAL` (proposed HTTP `422`); no new rows. To undo a mistaken reversal, the
     user posts the activity again under a new idempotency key.
8. **AC8 A correction is a reversal plus a new activity, applied atomically.**
   - Input: post `TP-0001-2025-0002` as in AC1 (amount mistyped as `amountMinor=301250`). Then submit a correction
     (proposed: `POST /v1/entries/{entryId}/correction`) with key `COR-TP-0001-2025-0002` and replacement activity
     `TP-0001-2025-0002-C1`, identical except `amountMinor=302150`.
   - Expected: exactly two new entries in one transaction: a reversal of the original and the new activity. CAD cash
     ends at `1000000 − 302150 − 495 = 697355`; `SYN-AAA` holding `100000000`.
   - Variant: the same correction but the replacement has `currency=EUR` (invalid). Expected: validation error; neither
     the reversal nor the new activity is written; CAD cash stays `698255`.
9. **AC9 Property test, invariant 4 (Reversal symmetry).**
   - Input: generated streams of valid activities, with a random subset then reversed, at least 1,000 cases.
   - Expected: final balances in every account and unit equal the balances from posting only the activities that were
     not reversed. Test output reports the number of cases.

## Out of scope

- Recomputing calculation-side pools after a back-dated correction (L-09).
- Publishing reversal events (L-05 covers all outbox events, including reversals).
- Partial reversals (reversing some lines of an entry). Not supported.
- ACB or any tax effect of a correction.

## Needs owner decision

- **Reversal of a reversal** (ADR 0003 open question). AC7 proposes refusing it. Alternative: allow it, which restores
  the original without a new key but makes chains harder to read.
- **Effective date of a reversal.** Proposal: the reversal takes the original's effective date for calculation order
  (it cancels the activity as of when it happened) and its own `recorded_at`. Alternative: the reversal's own date,
  which would leave the original in effect between the two dates. This matters for L-09 and replay.
- **Reversals that would make shares negative.** Example: buy 100 `SYN-AAA` on 2025-01-07, sell 60 on 2025-02-03,
  then reverse the buy. Refuse the reversal with `NEGATIVE_SHARES` (invariant 7), or allow it and let calculation flag
  it? Depends on where invariant 7 is enforced (see L-03).
- **Can an idempotency key be reused after its activity is reversed?** Proposal: no; the key stays bound to the
  original payload forever, and the correction uses a new key (as in AC8).
- **Correction endpoint.** Is the atomic correction in AC8 worth having, or is "reverse, then post new" as two calls
  enough? Two calls leave a window where neither version is in effect.

## Test map (QE fills)

| AC | Test |
| --- | --- |
| AC1 | `tests/spec/.../L04Spec.kt` › "L-04 AC1: …" |

## Change notes (Dev fills)

- …
