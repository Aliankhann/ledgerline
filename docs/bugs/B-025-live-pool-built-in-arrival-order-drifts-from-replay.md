# B-025 Live pool is built in arrival order, replay in tax-date order: seeded data drifts (TP-0001 SYN-CCC)

**Story:** L-12 (consumer) with L-10 (reconcile), found through the seed-on-start feature (C32) · **Severity:** major · **Status:** Fixed (QE verified, CI run 45078057691) · **Round:** 1

## Reproduction

1. Exact command(s) run (worktree at commit 0834d48, branch `l-seed`; no Docker):
   ```
   ./gradlew --init-script <mirror.init.gradle.kts> :tests:ktlintCheck --offline
   ./gradlew --init-script <mirror.init.gradle.kts> :tests:specTest --tests 'dev.ledgerline.spec.B025ConsumerArrivalOrderSpec' --offline
   ```
   Not run: the Integration test in the same spec (needs Docker; skipped locally, CI has it).
2. Minimal activity list for one taxpayer and security, delivered in this order (settlement date is the tax date, C6/C7):

   | # | key | type | trade date | settles | quantity | amount |
   | --- | --- | --- | --- | --- | --- | --- |
   | 1 | `TP-0001-B25-ROC` | RETURN_OF_CAPITAL | 2025-06-01 | 2025-06-03 | - | 100.00 |
   | 2 | `TP-0001-B25-BUY` | BUY | 2025-06-01 | 2025-06-02 | 10 | 1000.00 |

3. Seed 42: `SyntheticGenerator.generate(42, 5, 2025)` parsed by `CanonicalCsv`, entry ids 1..n in generator order, applied
   with the consumer's own `applyToPool` (via `InboxState`), versus `AcbEngine.replay`. TP-0001/SYN-CCC reproduced
   exactly as in CI run 45071551415. Its rows that matter for the drift (the other pool rows are two ROCs that hit the
   same pool state in both paths, 0012 on an empty pool and 0034 late in the year, and the rejected SELL 0027; TRANSFER_IN
   and DIVIDEND never enter a pool):

   | row | key | type | trade | settles | quantity (10^-6) | amount (cents) |
   | --- | --- | --- | --- | --- | --- | --- |
   | 2 | TP-0001-2025-0015 | RETURN_OF_CAPITAL | 2025-06-01 | 2025-06-03 | - | 39914 |
   | 3 | TP-0001-2025-0016 | BUY | 2025-06-01 | 2025-06-02 | 103550000 | 1504892 (+495) |
   | 5 | TP-0001-2025-0019 | BUY | 2025-06-29 | 2025-07-01 | 69360000 | 73799 (+495) |

   Row 0015 arrives before 0016 (the generator sorts by trade date and breaks the tie by key), but 0016 settles a day
   earlier than 0015, so in tax-date order the BUY comes first.

## Expected

Zero drift. Invariant 5 (replay is the reference) and invariant 6 (the final state does not depend on arrival
order): the live pool must equal `AcbEngine.replay` of the same activities, i.e. canonical order of tax date, then
`sourceSeq`, then key. Minimal repro: 10 shares, ACB 900.00 (9 x 10^8 internal units; 1000.00 less the 100.00 ROC).
Seed 42 TP-0001/SYN-CCC: 172910000 shares, `totalAcb` 15234050000.

## Actual

Live pool for the minimal repro: 10 shares, ACB 1000.00 (10^9 units); replay: 10 shares, 900.00 (9 x 10^8). Seed 42:

```
dev.ledgerline.spec.B025ConsumerArrivalOrderSpec > B-025: live pool equals replay when a ROC is delivered before a BUY that settles earlier FAILED
    Values differed at keys PoolKey(taxpayerId=TP-0001, securityId=SYN-AAA)
    expected:<{ PoolKey(...SYN-AAA) = (10000000, 900000000) }> but was:<{ PoolKey(...SYN-AAA) = (10000000, 1000000000) }>
dev.ledgerline.spec.B025ConsumerArrivalOrderSpec > B-025: seed 42 in generator order: live pools equal replay (TP-0001 SYN-CCC drifted in CI) FAILED
    expected:<(172910000, 15234050000)> but was:<(172910000, 15633190000)>
5 tests completed, 3 failed, 1 skipped          (skipped = the Integration test)
```

The seed 42 numbers match CI exactly (live 15633190000, replay 15234050000). The difference is 399140000 = the ROC
amount 399.14 x 10^4: live applied the ROC to a pool that was still empty (ROC absorbs 0, the excess is a deemed gain
that is not stored), then added the BUY at full cost. Shares agree because ROC never changes shares.
`./gradlew :tests:ktlintCheck`: BUILD SUCCESSFUL (run after the new spec was written).

## Root cause

`calc/src/main/kotlin/dev/ledgerline/calc/PoolConsumer.kt:156` (`handle`) calls `applyAndStore` (lines 186-218) for each
event in topic order. `applyAndStore` reads the stored `calc.pool` row (lines 193-205), applies the one activity with
`applyToPool` (`InboxState.kt:7-16`, `engine.apply`) and writes the row back (lines 207-217). That is a fold in
arrival (entry id) order. `AcbEngine.apply` is order dependent: a sell removes the pool's average ACB at that moment
and a ROC is capped at the ACB held at that moment (`AcbEngine.kt:76`, `:99`). The reference path sorts first:
`Replay.kt:25-37` `canonicalOrder` uses `ruleSet.taxDate` = `settlementDate ?: effectiveDate`
(`TaxRuleSet.kt:92`), then `sourceSeq`, then key. Whenever settlement dates invert the arrival order (here
trade-date order with a later settlement for the earlier row) the two folds differ.

`ReversalRecompute` (B-024 fix) already shows the right shape: it rebuilds the pool by `engine.replay` over the stored
history. The plain POSTED path never does, and `PoolRecomputer` (L-09) is not called by the consumer either. Not caused
by reversals, FX, registered accounts or the superficial loss path: none are involved in this pool.

## Production code that must change

`calc/` only: `PoolConsumer.applyAndStore` (and the shared `applyToPool` / `InboxState` model, which has the same
arrival-order fold and would otherwise keep passing the pure tests while the consumer stays wrong). Candidate fixes,
Dev's choice: (a) rebuild the touched (taxpayer, security) pool with `engine.replay` over the stored non-reversed
history up to the event's entry, as `ReversalRecompute.effectiveHistory` does, inside the same transaction; or
(b) replay only when the new activity's canonical position is before the pool's last applied change (needs the last
tax date / sequence stored with the pool). A sell rejected by the replay must not abort the consumer (the seed
contains one, see Notes); keep it a recorded rejection, not a throw. No change to `AcbEngine`, the replay, or the
tax rule set is needed: replay is the reference.

## Notes

- Why Docker was not needed: `InboxState.deliver` and `PoolConsumer.applyAndStore` both call `applyToPool`, so the
  arrival-order fold is reproducible in-process. `B-025 (integration)` runs the real consumer on the minimal pair and
  must be run by CI (`-PincludeIntegration`); I did not run it. The pure tests do not prove the consumer is fixed.
- Any seeded year is exposed: the generator orders by trade date but settles 0-2 days later at random
  (`SyntheticGenerator.kt:53`), so same-security activities within two days of each other can swap tax-date order.
  The compose reconcile step will fail for seed 42 until this is fixed.
- Observation, not claimed as a defect: in seed 42 the TP-0001/SYN-CCC SELL (0027, 176.000000 shares) is rejected
  by both paths for INSUFFICIENT_SHARES, because the generator counts TRANSFER_IN shares as held but
  `Replay.kt` `POOL_TYPES` and `PostedActivity.inPool` only pool BUY, SELL and ROC. `Reconciler` ignores
  `replay.rejections`, so this is invisible in the drift report. Whether TRANSFER_IN should enter the pool is an
  owner decision; it is in `docs/status.md` Open questions territory, not for QE to settle.
- Regression test file: `tests/spec/kotlin/dev/ledgerline/spec/B025ConsumerArrivalOrderSpec.kt` (4 pure tests, one
  of which passes by design as a check that the reference orders by tax date; 1 Integration test).

## Verification (QE, phase B, fix commit 24c78a0)

CI run 45078057691 is green including the Docker tests (reported by the owner's agent; not re-read by QE). Locally, no
Docker: `B025ConsumerArrivalOrderSpec` pure tests and the new attack tests in `B025FixAttackSpec` (rejected sell leaves
the pool as it was and later events apply; a late BUY turns an earlier rejected SELL into an applied one; redelivery
is a no-op whatever the payload; another security or taxpayer never alters a pool), plus the property test
`B025ArrivalOrderPropertyTest` (every permutation of short histories and shuffled long ones with redeliveries equal
`AcbEngine.replay`) pass. One new defect found beside the fix: see B-026 (a reversal stalls the consumer when the pool
holds a rejected sell). It does not reopen B-025.
