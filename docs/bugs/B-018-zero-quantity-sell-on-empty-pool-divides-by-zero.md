# B-018 Zero-quantity sell on an empty pool throws a divide-by-zero

**Story:** L-06 · **Severity:** minor · **Status:** Open · **Round:** 1

## Reproduction

1. Exact command(s) run:
   ```
   ./gradlew :tests:specTest --tests 'dev.ledgerline.spec.L06AttackSpec'
   ```
   Test: "L-06 attack: a zero-quantity sell on an empty pool does not crash the engine".
2. `AcbEngine.apply(AcbPool.empty(..), SELL with quantity 0 or null)`.

## Expected

`check` passes (0 <= 0 held), so the sell is a no-op or a rejection with a reason. No exception.

## Actual

```
java.lang.ArithmeticException: BigInteger divide by zero
    at dev.ledgerline.calc.AcbEngine.sell(AcbEngine.kt:71)
```
`totalAcb * sold / pool.shares` divides by `pool.shares == 0`. A whole replay aborts on one such activity.

## Notes

Minor because L-01/L-03 validation may refuse quantity 0 upstream; the pure engine should still not throw. A null quantity is treated as 0 by `check`, so a SELL without quantity hits the same path.
