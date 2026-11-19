# B-007 FixedPoint.format(Long.MIN_VALUE) wraps and returns garbage text

**Story:** L-03 (invariant 8; code from L-01 in `core/`) · **Severity:** minor · **Status:** Open · **Round:** 1

## Reproduction

1. Exact command(s) run:
   ```
   ./gradlew --init-script <mirror.init.gradle.kts> ktlintFormat build
   ```
2. Test: `L03ArithmeticOrderingEdgeSpec` › "L-03 edge (B-007): FixedPoint.format of Long.MIN_VALUE gives the exact
   value or fails, never garbage". Call `FixedPoint.format(Long.MIN_VALUE, 2, minimal = false)`.

## Expected

`"-92233720368547758.08"`, or a loud exception. Invariant 8 and the round-1 brief: overflow fails loudly, never wraps.

## Actual

```
expected:<"-92233720368547758.08"> but was:<"--92233720368547758.-8">
```

`format` computes `val abs = if (units < 0) -units else units`; `-Long.MIN_VALUE` wraps to `Long.MIN_VALUE`, so
`whole` and `frac` are both negative. `Long.MIN_VALUE + 1` and `Long.MAX_VALUE` format correctly (same test).

## Notes

Not reachable from `parse` (18 digits at most), but `format` is the reporting boundary for balances that the ledger
and calculation will sum. `Math.absExact` would make it loud. Also `pow10` multiplies without `multiplyExact`, so a
`decimals` of 20 or more would silently produce wrong text (constants are 2 and 6 today; not tested).
