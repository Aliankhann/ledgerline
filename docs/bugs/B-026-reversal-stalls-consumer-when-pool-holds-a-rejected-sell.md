# B-026 A reversal in a pool that holds any rejected sell stalls the consumer for good

**Story:** L-12 (consumer, reversal path) after the B-025 fix · **Severity:** major (reachable through the ledger, see Reachability) · **Status:** Fixed (calc fix dbd3d9b; QE verified, CI run 45088600142) · **Round:** 2

## Reproduction

1. Exact command(s) run (worktree at commit 24c78a0, branch `l-seed`; no Docker):
   ```
   ./gradlew --init-script <mirror.init.gradle.kts> :tests:specTest --tests 'dev.ledgerline.spec.B025FixAttackSpec' --offline
   ```
   Not run locally: the Integration tests in the same spec (need Docker; CI has it). Round 1's integration test
   (reverse a later BUY beside the rejected SELL) never reached the consumer: in CI run 45080884765 the ledger itself
   refused the reversal (`ReversalRefusedException: NEGATIVE_SHARES: entry 3`, test setup). That was my scenario error,
   not a calc defect; see Reachability for which reversals the ledger does accept.
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

## Reachability through the ledger (round 2; from reading `ledger/` and `calc/`, not observed: no Docker locally)

`LedgerRepository.reverse` runs `ShareHoldings.requireNonNegativeWithout` (C30). It walks the reversing account's own
holdings of each `SEC:` unit in the reversed entry (and a correction's replacement), in canonical order, with the
reversed entry and already-reversed pairs left out, and refuses with `NEGATIVE_SHARES` if the running total ever goes
below 0. Posting a SELL is not checked. The calc pool counts only BUY, SELL and RETURN_OF_CAPITAL on NON_REGISTERED
accounts (`Replay.POOL_TYPES`, `inPool()`), per taxpayer and security across accounts. So the two views of holdings differ,
and the stall is reachable in production. Two concrete sequences, both for TP-0001 / SYN-AAA, account TP-0001-NR1:

1. **ROC reversal.** BUY 10 (03-03), SELL 50 (03-04, over-sell: the ledger posts it, every calc replay rejects it),
   RETURN_OF_CAPITAL 10000 cents (03-05), then `reverse(ROC)`. A ROC has only cash lines, so the ledger's walk has no
   share unit to check and accepts the reversal, although the account's own holdings are already 10 - 50 = -40. The
   consumer then reaches `ReversalRecompute.apply` for a pool holding a rejected SELL. (Before the dbd3d9b fix: throw, retry forever.)
2. **TRANSFER_IN covers the ledger, not the pool** (the seed-42 shape). TRANSFER_IN 50 (03-02), BUY 10 (03-03), SELL 50
   (03-04), BUY 5 (03-05), then `reverse(BUY 5)`. Ledger holdings without the reversed entry: 50, 60, 10, never below 0,
   so it accepts. The pool ignores TRANSFER_IN, holds 10, and rejects the SELL both before and after the reversal.

Not reachable: with an over-sell and no other source of shares in the account, reversing any BUY or SELL of that account
and security is refused by the ledger (this is what CI run 45080884765 hit). A third route, not tested: shares bought in a
registered account of the same taxpayer are not in the NON_REGISTERED pool, and the ledger's walk is per account, so it
differs again.

Severity stays major: a legitimate reversal (sequence 1 needs nothing unusual beyond one data-entry over-sell, which the
generator and the ledger both allow) stops every later event on the consumer. If the owner treats over-sells as
"never in production data", this drops to minor, with the calc fix kept as defence in depth; that is the owner's call.

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

## Change notes

Dev: `ReversalRecompute.apply` now also replays the history before the reversal with the reversed activity still in
(same query, reversed entry not excluded) and throws only for rejections (key + code) that the post-reversal replay
has and the pre-reversal replay does not. Rejections that already existed are carried as in a normal replay. B-024
behaviour (a reversal that newly causes a rejection) is unchanged. Only `calc/.../ReversalRecompute.kt` changed.

## Round 2 tests (QE)

`B025FixAttackSpec` reworked: the Integration test that tried to reverse a BUY beside an over-sell (refused by the
ledger) is replaced by two that follow the sequences above, each asserting zero drift against `Reconciler` over the
remaining activities, the exact inbox count, and (sequence 2) that one more event after the reversal is applied:

- `B-025 attack 1+2 (integration): TRANSFER_IN covers the ledger, not the pool; reverse a BUY; zero drift`
- `B-025 attack 1+2 (integration): ROC has no share line; reverse it beside a rejected SELL; zero drift`
- plus a scripted-JDBC test with a TRANSFER_IN in the history (passes at dbd3d9b; guards the calc side without Docker).

Ran at dbd3d9b with the mirror init script and `--offline`: `:tests:ktlintCheck`, `:tests:compileTestKotlin`,
`:tests:specTest` all BUILD SUCCESSFUL; the B-025 spec shows 7 PASSED and the 2 integration tests SKIPPED (no
`-PincludeIntegration`). Not run: the two integration tests (Docker). Their ledger-side acceptance is derived from
reading `ShareHoldings`, not observed.
