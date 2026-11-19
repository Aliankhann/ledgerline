# B-005 Whether an entry is accepted near Long.MAX depends on the order of its lines

**Story:** L-03 (AC5; invariants 1, 5 and 8) · **Severity:** minor · **Status:** Open · **Round:** 1

## Reproduction

1. Exact command(s) run:
   ```
   ./gradlew --init-script <mirror.init.gradle.kts> ktlintFormat build
   ```
2. Test: `L03ArithmeticOrderingEdgeSpec` › "L-03 edge (B-005): whether an entry is accepted does not depend on the
   order of its lines". The same four CAD lines, D = debit, C = credit, MAX = `Long.MAX_VALUE`:
   `Posting.requireBalanced(listOf(D MAX, C MAX, D 1, C 1))` versus `listOf(D MAX, D 1, C MAX, C 1)`.

## Expected

The outcome depends only on the set of lines: both orders are accepted, or both are refused with one documented
reason (for example "total debits in CAD exceed the representable range").

## Actual

```
outcome per line order: {D9223372036854775807, C9223372036854775807, D1, C1=accepted,
  D9223372036854775807, D1, C9223372036854775807, C1=ArithmeticException,
  C9223372036854775807, C1, D9223372036854775807, D1=accepted, D1, C1, D9223372036854775807, C9223372036854775807=accepted}
expected:<1> but was:<2>
```

`requireBalanced` keeps a running signed sum with `Math.addExact` (ledger/.../Posting.kt), so it overflows only when
the running partial sum passes Long.MAX, which depends on line order. It never wraps (the property test
`L03BalanceArithmeticPropertyTest` passes 2000 cases against a BigInteger oracle), so this is about consistency, not
silent corruption.

## Notes

Impact: an entry accepted once could be refused on replay if anything reorders its lines (invariant 5), and an
accepted entry can have total debits above Long.MAX, which any Long-based total (reports, reconciliation) cannot
hold. Summing debits and credits separately with `addExact` would refuse both orders consistently.
