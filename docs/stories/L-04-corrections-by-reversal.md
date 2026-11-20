# L-04 Corrections by reversal

**Status:** QE passed (round 3 + C19; CI integration green), awaiting owner approval  <!-- Draft → Approved → Tests written → In dev → In QE → Done. Only the owner sets Approved. -->
**Day:** 3 · **Branch:** `L-04-corrections-by-reversal` · **Round:** 0 / 3

## Story

As a self-directed investor, I want to correct a wrong activity by reversing it and posting the right one, so that my
history keeps both the mistake and the fix and my balances end up exactly as if only the right one had happened.

## Why it matters

Late and corrected broker data is normal, and the audit trail must survive corrections. See
[`docs/problem/evidence.md`](../problem/evidence.md): E-18 (Wealthsimple's ledger: "corrections are compensating
entries, never edits") and E-32 (annual tax adjustments and amended slips arrive after the fact).

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

Written provisionally while the story is Draft (owner instruction). API contract is in the KDoc of
`L04ReversalSpec`. HTTP codes are not tested (endpoint names not approved); every AC is tested at repository level.
Placeholders: C16 (`REVERSAL_OF_REVERSAL`), C17 (reversal carries original's effective date), C18 (keys never
reusable), reversals that would go negative are refused `NEGATIVE_SHARES`, `UNKNOWN_ENTRY`, `ALREADY_REVERSED`.
`S` = `tests/spec/kotlin/dev/ledgerline/spec/L04ReversalSpec.kt`;
`P` = `tests/property/kotlin/dev/ledgerline/property/ReversalSymmetryPropertyTest.kt`.

| AC | Test |
| --- | --- |
| AC1 | S "L-04 AC1: apply then reverse returns every balance to its prior value; the reversal is balanced" (Integration); S "L-04 AC1: the reversal lines are balanced per unit and cancel the original exactly" |
| AC2 | S "L-04 AC2: Posting.reversalOf mirrors every line ..."; S "L-04 AC2: the stored reversal has the original's lines, one for one ..." (Integration) |
| AC3 | S "L-04 AC3: the original's rows hash the same before and after; the link lives on the reversal" (Integration) |
| AC4 | S "L-04 AC4: reversing an entry that does not exist is refused UNKNOWN_ENTRY; nothing is written" (Integration). Id is a `Long` sequence, not a UUID |
| AC5 | S "L-04 AC5: the same reversal request twice is a no-op ..."; S "L-04 AC5 (invariant 2): the reversal key reused for a different entry is rejected ..." (both Integration) |
| AC6 | S "L-04 AC6: a second, differently keyed reversal ... ALREADY_REVERSED, naming the first" (Integration) |
| AC7 | S "L-04 AC7 (C16 placeholder): reversing a reversal is refused ..."; S "L-04 AC7 (C16, C18 placeholders): ... post again under a NEW key" (both Integration) |
| AC8 | S "L-04 AC8: a correction is a reversal plus a new activity ..."; "same correction key twice"; variant "invalid replacement (BUY without a quantity)"; variant "replacement whose key already exists rolls the reversal back too"; variant "unknown or already reversed entry" (all Integration). EUR cannot be built (`Currency` is CAD/USD), see KDoc |
| AC9 | P "Invariant 4 (L-04 AC9): reverse a random subset ... (1000 cases)" (Integration) and P "... (L-04 AC9, pure) ... (1000 cases)"; both print the case count |
| C17 | S "L-04 C17 placeholder: the reversal carries the original's effective date ..." (Integration) |
| NEGATIVE_SHARES | S four tests "NEGATIVE_SHARES: ..." (refused, reverse sell first, per effective date, boundary at 0) (Integration); also exercised in P |

## Change notes (Dev fills)

- 2026-11-19 · dev · Round 0 implementation, provisional on placeholders C16 (`REVERSAL_OF_REVERSAL`), C17 (reversal
  carries the original's effective date and source sequence), C18 (keys never reused), all marked `// OWNER-INPUT`
  and listed in `docs/owner-inputs.md`; `NEGATIVE_SHARES` follows invariant 7. Flyway `V5__reversals.sql` adds the
  append-only table `ledger.reversal` (reversal entry id, original entry id UNIQUE, reason); the original's rows are
  never touched and `REVERSED` is derived from the link. Triggers: the link must be written in the reversal entry's
  own transaction, the original cannot itself be a reversal, and at commit the reversal's lines must mirror the
  original's. No foreign keys on `reversal` (a FK would make Postgres refuse `TRUNCATE ledger.entry, ledger.line`
  before the append-only trigger, which L-03's edge spec asserts); a trigger checks existence instead. Grants:
  `ledgerline_app` has SELECT and INSERT only on the new table (no new sequences). `ledger`: `Posting.reversalOf`,
  `LedgerRepository.reverse/statusOf/correct` (one transaction each, under the existing append lock),
  `ReversalRefusedException`, `ShareHoldings` (walks the investor account's share movements in calculation order,
  leaving out reversed pairs). `correct()` also counts the replacement in that walk, so correcting a buy that a later
  sell depends on works when the replacement keeps enough shares (a judgment call beyond the spec). Not done: HTTP
  endpoints (names not approved; tests are repository-level).
- **Test problem, for QE.** `L04ReversalSpec` and `ReversalSymmetryPropertyTest` do not compile as committed:
  `LineMath.imbalances` and `Balances.of` return maps but the files import `io.kotest.matchers.collections.shouldBeEmpty`
  (needs `io.kotest.matchers.maps.shouldBeEmpty`), and `L04ReversalSpec` line 210 imports `maps.shouldContainAll` for a
  `Set` (needs `collections.shouldContainAll`). Dev did not edit `tests/`. With only those imports changed in a scratch
  copy, `./gradlew build` and `:tests:test -PincludeIntegration` pass (106 tests, 1 skipped (B-006), 0 failed). See
  `docs/status.md` Open questions.
- 2026-11-19 · qe · Phase B round 1. Fixed the import errors in `L04ReversalSpec` and `ReversalSymmetryPropertyTest`.
  Attack specs added: `L04ReversalConcurrencyEdgeSpec` (8 threads, 5 rounds each: one reversal per entry, same-key
  retries share one result, correct() the same), `L04ReversalDependencyEdgeSpec` (sell depends on buy; per-account;
  huge holdings), `L04ReversalDatabaseEdgeSpec` (app role cannot UPDATE/DELETE/TRUNCATE `ledger.reversal`; raw inserts
  through the app role never leave a link to a missing or unmirrored entry; replay after reversals is
  arrival-order independent). Defects: B-008 (minor), B-009 (major, owner decision likely), B-010 (minor), tests left
  failing. **Owner decision, not a bug:** `correct()` counts the replacement in the NEGATIVE_SHARES check, so
  correcting a buy that a later sell depends on is allowed when the replacement keeps enough shares, while `reverse()`
  of the same buy is refused. Pinned both ways in `L04ReversalDependencyEdgeSpec`.
