# B-024 PoolConsumer records ENTRY_REVERSED but changes no pool, so live pools drift after a reversal

**Story:** L-12 · **Severity:** major · **Status:** Open · **Round:** 1

## Reproduction

1. Exact command(s) run:
   ```
   ./gradlew --init-script <mirror.init.gradle.kts> :tests:specTest --tests 'dev.ledgerline.spec.L12ReversalGapSpec'
   ./gradlew :tests:specTest -PincludeIntegration --tests 'dev.ledgerline.spec.L12ReversalGapSpec'   (Docker; not run here)
   ```
2. Post a BUY and a SELL, consume both, reverse the SELL (`LedgerRepository.reverse`), consume the ENTRY_REVERSED event.
3. `Reconciler.reconcile(PoolStore.pools(), [BUY])` (C19 replay skips the reversed pair).

## Expected

Zero drift: pool holds the BUY's 100 shares and full ACB (invariants 4 and 5).

## Actual

`PoolConsumer.handle` calls `EventDecoder.decode`, which returns null for ENTRY_REVERSED, so only the inbox row and
offset are written. The pool keeps the SELL's effect (60 shares): drift in `shares` and `totalAcb`. The payload also
carries only `reversesEntryId`, not the original's `activityId`, so the consumer cannot map it to a pool change today.

## Notes

Story change notes admit "reversal and correction handling is L-09". L-12's exactly-once claim is then only true for
posted activities. Fix needs either recompute through `PoolRecomputer` on reversal or a documented owner decision.

## Follow-ups for the story change notes (not defects yet)

- `PoolConsumer` is not wired into `app/` or `docker-compose.yml`, so nothing runs it in the stack.
- `CalcMigrations.migrate` must run after `LedgerMigrations` (it grants DML to the ledger's app role); no code enforces it.
