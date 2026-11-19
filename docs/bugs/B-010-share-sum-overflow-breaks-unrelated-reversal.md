# B-010 Reversing a small buy fails with ArithmeticException when the account's other holdings exceed Long.MAX

**Story:** L-04 (NEGATIVE_SHARES check, invariants 7 and 8) · **Severity:** minor · **Status:** Open · **Round:** 1

## Reproduction

1. Exact command(s) run:
   ```
   ./gradlew --init-script <mirror.init.gradle.kts> :tests:test -PincludeIntegration
   ```
2. Test: `L04ReversalDependencyEdgeSpec` › "L-04 edge: huge holdings: reversing an unrelated small buy is not broken by
   the sum of other holdings". Post two buys of `6_000_000_000_000_000_000` units of `SYN-AAA` and one of
   `1_000_000` units for `TP-0001-NR1`; reverse the small one.

## Expected

The small buy is reversed (the remaining holdings stay positive), or the reversal is refused with a documented
`ReversalRefusedException` code. Never an unclassified exception.

## Actual

```
Failure(java.lang.ArithmeticException: long overflow)
```

`ShareHoldings.requireNonNegativeWithout` keeps a running `Math.addExact` total of the account's remaining holdings.

## Notes

Unrealistic quantity (6 trillion shares at the 10^-6 scale), nothing is written, and the failure is loud, so minor.
Decide whether such quantities should be refused at posting (then this cannot arise) or the walk should use an
overflow-safe comparison.
