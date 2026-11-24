# B-039 Preview throws ArithmeticException (500) for a valid row whose amount the ACB engine cannot scale

**Story:** L-20 (AC4 warnings, AC1/AC2 preview of a file the import accepts) · **Severity:** minor · **Status:** Open · **Round:** 1

## Reproduction

1. Exact command(s) run (worktree `main-wt`, branch `l-20`, HEAD 6f76710):
   ```
   ./gradlew --init-script ../mirror.init.gradle.kts :tests:specTest --tests '*L20Attack*' --offline
   ```
   Test: "L-20 attack 6b: the largest 18-digit amount and commission classify as NEW and one more digit as REJECTED".
2. A file with one BUY, `amount` `9999999999999999.99`, `commission` `9999999999999999.99` (the largest value the
   parser accepts), key `S-2`, on an empty ledger. The ledger stores it (cash is `Long` minor units; B-037 notes the same).

## Expected

Preview answers with the row `NEW` (the import would post it). If the in-memory ACB replay cannot handle the value,
the preview should say so (a warning, or an `AMOUNT_OUT_OF_RANGE`-style code as B-037 chose for the tax-year routes),
not throw.

## Actual

```
classify threw java.lang.ArithmeticException: long overflow
  Math.multiplyExact <- AcbEngine.buy(AcbEngine.kt:66) <- AcbEngine.apply(AcbEngine.kt:41)
  <- Replay.replayAll(Replay.kt:54) <- AcbEngine.replay(AcbEngine.kt:55) <- ImportPreview.warningsCaused(ImportPreview.kt:182)
```

`POST /v1/imports/preview` would answer 500 for the whole file (by code reading; the route has no handler, and the
HTTP specs need Postgres, not run here), although `importCsv` and confirm post the row.

## Notes

- Same root cause as B-037 (`AcbEngine.buy`: `amount + commission` times the ACB unit factor overflows near
  `Long.MAX / 10^4` cents). The engine failing loudly is correct (B-007). The defect is that `warningsCaused` runs the
  replay with no guard, so one extreme row hides the classification of every other row in the file. The replay also
  runs over the taxpayer's stored rows, so one extreme stored row would make every later preview of that taxpayer throw.
- Severity minor: the amount is about $10 quadrillion. Fixed the way B-037 was, a guard around the replay is enough.
