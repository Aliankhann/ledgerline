# B-027 TRANSFER_IN-only security loses its DATA-GAP-TRANSFER-NO-COST warning

**Story:** L-16 · **Severity:** major · **Status:** Open · **Round:** 1

## Reproduction

1. Exact command(s) run:
   ```
   ./gradlew --init-script <mirror.init.gradle.kts> :tests:specTest --tests 'dev.ledgerline.spec.L16AttackSpec' --offline
   ```
   Test: "L-16 attack: a TRANSFER_IN-only security with no book value still shows its warning on the route".
2. Input: one non-registered `TRANSFER_IN` of 100 XYZ (entry 1), no BUY of XYZ, an empty book-value CSV
   (`security_id,book_value` header only). `GET /v1/holdings/TP-0001/acb-vs-book` via `acbVsBookFromActivities`.

## Expected

The XYZ holding is listed with a `DATA-GAP-TRANSFER-NO-COST` reason, `entryIds = [1]`. This is the case the story
exists for: shares in the file with no cost. `DataGapWarnings.detect` does return the warning for this input.

## Actual

`"holdings":[]`. `TRANSFER_IN` never reaches a pool (`Replay.kt` filters to BUY/SELL/ROC), so there is no pool key and no
book-value key. `BookValueComparison.compareAll` iterates only `pools.keys + bookValues.keys`, so the warning keyed
`TP-0001/XYZ` is dropped. The route's `known` check (`pools.keys.any { ... }`) also makes a taxpayer whose only
activity is transfers "unknown", so the whole body is empty.

```
Collection should contain element "XYZ" based on object equality; but the collection is []
  at L16AttackSpec.kt:140
```

## Notes

Warnings only show when the same security also has a BUY/SELL/ROC (a pool) or a book value. A warning on a security with
neither is silent. Fix direction (Dev's call): include warning keys in the row set in `compareAll`, and count them for
the route's `known` check. Does not touch C33 (cost of transfers); no pool rule changes.
