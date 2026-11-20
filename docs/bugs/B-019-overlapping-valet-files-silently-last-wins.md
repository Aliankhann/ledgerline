# B-019 Same date in two overlapping Valet files: last file silently wins

**Story:** L-08 · **Severity:** major · **Status:** Open · **Round:** 1

## Reproduction

1. `./gradlew :tests:test --tests '*L08FxRobustnessTest*'` (test "the same date in two overlapping files with different rates is not silently resolved")
2. Two `ValetResponse`s both containing `2025-03-05`, with `v` 1.4370 and 1.9999, passed to `FxRateCache.fromValetJson`.

## Expected

Conflicting rates for one date are rejected (`IllegalArgumentException`); identical duplicates are accepted. The conversion of tax amounts must not depend on file order.

## Actual

`rates[date] = ...` overwrites; no exception, the rate depends on list order.
```
AssertionError: Expected exception java.lang.IllegalArgumentException but no exception was thrown.
```

## Notes

Breaks invariant 5/6 flavour (result depends on input order).
