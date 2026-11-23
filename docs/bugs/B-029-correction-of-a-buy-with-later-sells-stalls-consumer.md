# B-029 A correction of a BUY that a later SELL depends on stalls the consumer for good

**Story:** L-09 (AC7, AC8) via L-12 consumer reversal path · **Severity:** major (reachable through `LedgerRepository.correct`; stalls every taxpayer on the consumer) · **Status:** Fixed (pending CI) · **Round:** 1

## Reproduction

1. Commands run: none locally (no Docker). Observed in CI run 45227826239, integration job, 451 tests, 2 failed:
   `L09GapSpec` "L-09 AC7: redelivering the reversal and replacement events changes no pool and no inbox row" and
   "L-09 AC8: seed 42 year, then 20 random back-dated corrections and late activities: zero drift after each".
   Root cause found by reading code, not by running it.
2. Minimal sequence (story L-09 fixture F1, TP-0001 / SYN-AAA, account TP-0001-NR1):

   | entry | key | type | date | shares |
   | --- | --- | --- | --- | --- |
   | 2 | `TP-0001-2025-0002` | BUY | 2025-03-10 | 100 |
   | 5 | `TP-0001-2025-0004` | SELL | 2025-06-02 | 60 |
   | 8 | `TP-0001-2025-0002-COR` | reversal of entry 2 | 2025-03-10 | |
   | 9 | `TP-0001-2025-0002-C1` | replacement BUY | 2025-03-10 | 110 |

   (entries 8 and 9 come from one `repo.correct(2, "TP-0001-2025-0002-COR", "late fix", replacementBuy110)`.)
   The ledger accepts it: `ShareHoldings.requireNonNegativeWithout` (ledger/.../ShareHoldings.kt:23-56) adds the
   replacement's lines to the account's moves, so 110 - 60 >= 0. Then publish both outbox events and run
   `PoolConsumer.pollOnce`.

## Expected

The correction is applied. Pool of TP-0001/SYN-AAA = 110 - 60 + 20 = 70 shares (story AC1). Consumer commits the inbox
rows and moves on. AC8: reconcile reports 0 drift after each correction.

## Actual

```
java.lang.IllegalStateException: reversal of entry 2 makes the replay of TP-0001/SYN-AAA reject:
[Rejection(idempotencyKey=TP-0001-2025-0004, code=INSUFFICIENT_SHARES, held=0, requested=60000000 ...)]
  at dev.ledgerline.calc.ReversalRecompute.apply(ReversalRecompute.kt:29)
  at dev.ledgerline.calc.PoolConsumer.handle(PoolConsumer.kt:157)
  at dev.ledgerline.calc.PoolConsumer.pollOnce(PoolConsumer.kt:65)
```

AC8 fails the same way (two sells newly rejected: TP-0001-2025-0004 and TP-0001-2025-0018, held=0).
`handle` rolls back and rethrows; `pollOnce` re-seeks to the stored offset, so the same event is retried forever and
every later event on that consumer is stuck (same failure mode as B-026).

## Root cause

- `LedgerRepository.correct` (ledger/.../LedgerRepository.kt:182-) writes the reversal (`appendReversal`, outbox
  `ENTRY_REVERSED`) and then the replacement (`insert` + `OutboxWrites.activityPosted`) in one transaction, with the
  replacement at a higher entry id. The ledger's non-negative check treats the pair as one unit.
- `PoolConsumer.handle` (PoolConsumer.kt:157) handles the `ENTRY_REVERSED` event alone.
  `ReversalRecompute.apply` (ReversalRecompute.kt:19-31) replays only entries `< reversalEntryId` (`effectiveHistory`,
  `a.entry_id < ?`) without the reversed one, so the replacement (entry id above the reversal) is not yet visible.
  The later SELL then has 0 shares, the replay rejects it, and the B-026 rule (`introduced.isEmpty()` check at line
  27-29) throws, because that rejection is not in the "before" replay.
- The rule from B-026 is right for a standalone reversal, which the ledger refuses with NEGATIVE_SHARES in that case.
  It is wrong for the first half of a correction. The consumer cannot tell the two apart: the `ENTRY_REVERSED` payload
  carries no link to the replacement.

## Test check

The tests are valid. AC7 calls `repo.correct` with F1 and AC1's replacement (110 shares); AC8 calls `repo.correct` for
odd n. This is the production API. Posting, outbox relay and consumer are the real classes. Nothing in the test does
something the service cannot. (`correct` has no HTTP caller in app/ today; it is the ledger's public API.)
Why L09RecomputeSpec AC1 passes: it drives the pure `PoolRecomputer`, not the consumer's per-event path.

## Fix direction (no code prescribed)

The consumer must treat a reversal and its replacement as one unit. Options for Dev and the owner: (a) the
reversal event carries the replacement's entry id, or the consumer finds a replacement in the same ledger transaction,
and applies the recompute once with the replacement included; (b) the reversal step records but does not throw on
rejections while a replacement is still due, with the check made after the replacement event; (c) the
reversal-introduced-rejection check only runs for reversals the ledger posted alone. Any choice needs an ADR, since
event shape or consumer semantics change. A standalone reversal that introduces a rejection must keep its B-026 behaviour.

## Notes

- Not run locally: all of L09GapSpec (Integration, needs Docker). CI log: integ.log lines 5809 and 5885.
- AC6 passes (refused correction writes nothing).

## Fix note (dev)

Chosen: a link row, no new event shape (draft ADR 0007). Migration `V9__correction_link.sql` adds append-only
`ledger.correction`; `LedgerRepository.correct` inserts the (reversal, replacement) pair in its transaction;
`ReversalRecompute.apply` rebuilds up to the replacement when a link exists, so the introduced-rejection check sees the
state after the whole correction. Reversals without a link behave as in B-026. Not run: L09GapSpec (needs Docker; CI).
