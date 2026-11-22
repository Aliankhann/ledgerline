# B-026 A reversal in a pool that holds any rejected sell stalls the consumer for good

**Story:** L-12 (consumer, reversal path) after the B-025 fix · **Severity:** major · **Status:** Open · **Round:** 1

## Reproduction

1. Exact command(s) run (worktree at commit 24c78a0, branch `l-seed`; no Docker):
   ```
   ./gradlew --init-script <mirror.init.gradle.kts> :tests:specTest --tests 'dev.ledgerline.spec.B025FixAttackSpec' --offline
   ```
   Not run: the Integration test in the same spec (`B-025 attack 1+2 (integration)`; needs Docker, CI has it). It
   exercises the same path end to end and is expected to fail in CI for the same reason.
2. One taxpayer and security (TP-0001 / SYN-AAA), activities posted to the ledger in this order:

   | entry | key | type | date | quantity | note |
   | --- | --- | --- | --- | --- | --- |
   | 1 | `U-B1` | BUY | 2025-03-03 | 10 | |
   | 2 | `U-S1` | SELL | 2025-03-04 | 50 | more than held: every replay rejects it (invariant 7) |
   | 3 | `U-B2` | BUY | 2025-03-05 | 5 | |
   | 4 | reversal of entry 3 | | | | `ledger.reversal(original=3, reversal=4)` |

   The ledger accepts the SELL (it does not check holdings; the seed contains one, see B-025 notes). The consumer
   handles entries 1-3 fine since the B-025 fix (a rejection is recorded in the replay, never thrown). On entry 4
   `PoolConsumer.handle` calls `ReversalRecompute.apply(c, 3, 4)`, which rebuilds from entries 1-2.
3. The scripted-JDBC test drives `ReversalRecompute.apply` with exactly the history the real query returns for
   step 2 (entries before 4, reversed entry 3 excluded): `runReversalRecompute(engine, ledger, 3, 4)` in
   `tests/support/kotlin/dev/ledgerline/testsupport/ScriptedJdbc.kt`. The fake answers the two SELECTs by SQL text; it
   does not evaluate the real WHERE clauses, so the history is what the real query would return, by construction.

## Expected

The reversal is applied: the pool becomes the replay of what remains, 10 shares (entry 1; the SELL stays rejected, as
it was before the reversal), the inbox row and offset are committed and the consumer moves on. A rejection that was
already there before the reversal, and that the reversed activity has nothing to do with, must not block it.

## Actual

```
dev.ledgerline.spec.B025FixAttackSpec > B-025 attack 2 (scripted JDBC): a reversal of a BUY is applied although the pool holds a rejected sell FAILED
    java.lang.IllegalStateException: reversal of entry 3 makes the replay of TP-0001/SYN-AAA reject: [Rejection(idempotencyKey=U-S1, code=INSUFFICIENT_SHARES, held=10000000, requested=50000000, message=Sell rejected: 10000000 shares held, 50000000 requested (10^-6))]
        at dev.ledgerline.calc.ReversalRecompute.apply(ReversalRecompute.kt:22)
```

In the real consumer the exception rolls the transaction back, `pollOnce` re-seeks from the stored offset, and
`start()`'s loop retries the same record every 500 ms forever. The offset never advances, so every later event for every
taxpayer on that consumer is stuck behind it (no pool changes, reconcile drift grows). Nothing in the log except the
repeated WARNING.

## Root cause

`calc/src/main/kotlin/dev/ledgerline/calc/ReversalRecompute.kt:19-23` (`apply`) throws if `replay.rejections` is not
empty. That check was written for B-024, where the reversal itself could make a later sell exceed the holding. It
cannot tell such a rejection from one that was already in the history. Before B-025 a rejected POSTED sell never
reached a replay in the consumer, so the check was rarely hit; since the B-025 fix the consumer replays whole
histories, and the generator and the ledger both allow a rejected sell (seed 42 TP-0001/SYN-CCC row 0027).

## Production code that must change

`calc/` only: `ReversalRecompute.apply`. Dev's choice, for example: fail only on rejections that are new relative to
a replay of the same history with the reversed activity still in (the reversal caused them), or never throw and record
the rejections as `rebuild` does for POSTED events. Which of the two is right when the reversal itself causes a
rejection (B-024's original intent) is an owner/ADR question if the answer is not already in ADR 0006; QE does not
decide it.

## Notes

- Regression tests: `B025FixAttackSpec` "B-025 attack 2 (scripted JDBC): a reversal of a BUY is applied although the
  pool holds a rejected sell" (fails now, observed) and the Integration test of the same spec (not run locally).
- The reflection call into the `internal` class is test-side only; if `ReversalRecompute` is renamed the test fails
  with a reflection error, not a false pass.
