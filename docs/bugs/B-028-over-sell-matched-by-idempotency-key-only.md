# B-028 DataGapWarnings.detect matches rejected sells by idempotency key only

**Story:** L-16 · **Severity:** minor (latent) · **Status:** Fixed (QE verified, CI run 45117734937) · **Round:** 1

## Reproduction

1. Exact command(s) run:
   ```
   ./gradlew --init-script <mirror.init.gradle.kts> :tests:specTest --tests 'dev.ledgerline.spec.L16AttackSpec' --offline
   ```
   Test: "L-16 attack: the same key on another taxpayer's accepted sell is not flagged".
2. Input to `DataGapWarnings.detect(acts, engine.replay(acts))`: TP-A `SELL` 5 XYZ key `dup` (nothing held, rejected);
   TP-B `BUY` 10 XYZ; TP-B `SELL` 5 XYZ key `dup` (accepted).

## Expected

Only `TP-A/XYZ` carries `DATA-GAP-OVERSELL`.

## Actual

Both `TP-A/XYZ` and `TP-B/XYZ` are flagged: `detect` builds `rejected` from `Rejection.idempotencyKey` and flags every
activity with that key (`DataGapWarnings.kt`: `it.activity.idempotencyKey in rejected`).

```
Collection should contain exactly: [PoolKey(taxpayerId=TP-A, securityId=XYZ)] but was: [..TP-A.., ..TP-B..]
```

## Notes

Latent: `ledger.activity.idempotency_key` is `UNIQUE` (V4 migration), so the service cannot hold two rows with one key.
It is reachable by any caller of the public `detect`, and by CSV input that is not yet de-duplicated. A false warning
on a clean holding is the worse direction for a warning feature. Fix direction: match on `activityId` (as
`plainRejections` in `SuperficialReplay.kt` does) or on key plus taxpayer plus security. Owner may mark Won't fix given
the DB constraint.

## Fix note (Dev, round 2)

`Rejection` gained trailing defaulted fields `taxpayerId` and `securityId`, filled by `AcbEngine.check`. `detect` now
matches on (taxpayerId, securityId, idempotencyKey). The L-06 constructor shape is unchanged for existing callers.
