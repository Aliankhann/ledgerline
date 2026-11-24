# B-037 One huge amount makes every tax-year route throw an unhandled ArithmeticException

**Story:** L-18 (AC5 robustness, AC7) · **Severity:** minor · **Status:** Fixed (QE verified, round 2) · **Round:** 2

## Reproduction

1. Exact command(s) run:
   ```
   ./gradlew --init-script ../mirror.init.gradle.kts :tests:specTest --tests '*L18Attack*' --offline
   ```
   Test: "L-18 attack 10: one huge amount does not turn the whole tax year or the years list into a 5xx".
2. Book for one taxpayer: a normal security (BUY 5 shares 5000, SELL 5 shares 9000 in 2025) plus SYN-HUGE with a BUY of
   amount 2,000,000,000,000,000 cents (2e15; the ledger accepts amounts up to `Long.MAX`, `Posting.kt`) and a SELL in 2025.
   Request `GET /v1/tax-year/TP-0001` (years list).

## Expected

A 4xx or 5xx response with a message naming the security or entry, or the normal security's rows still reported. At
least no unhandled exception that takes down the years list and every year for the taxpayer.

## Actual

```
GET /v1/tax-year/TP-0001 -> EXCEPTION ArithmeticException: long overflow
```

Raised in `AcbEngine.buy` (`Math.multiplyExact(amount + commission, acbUnitsPerCent)`), reached through
`TaxYearReport.yearsWithRows` -> `allRows` -> `replaySuperficial`.

## Notes

- Only the years route was observed (the test stops at its first failure). `/{year}` and `/{year}.csv` call
  `TaxYearReport.build`, which runs the same `replaySuperficial`, so by code reading they throw too; QE did not run them
  separately.
- Failing loudly on overflow is the repo's rule (B-007), and the engine is not at fault. The defect is that the route has
  no handler, so one poisoned activity hides all the taxpayer's other securities, as in B-035 for L-17. Severity is minor
  because the amount is about $20 trillion; the same path fails for any total ACB near `Long.MAX / 10^4` cents.

## Round 2 (QE)

Verified: years list, JSON and CSV routes each return 422 with `AMOUNT_OUT_OF_RANGE` for the huge-amount book
("L-18 attack 12" passes; "attack 10" passes).
