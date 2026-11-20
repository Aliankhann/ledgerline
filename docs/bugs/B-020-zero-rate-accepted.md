# B-020 A rate of 0 ("0" or "0.0000") is accepted and converts every USD amount to 0 CAD

**Story:** L-08 · **Severity:** major · **Status:** Fixed · **Round:** 1

## Reproduction

1. `./gradlew :tests:test --tests '*L08FxRobustnessTest*'` (test "missing or malformed v is rejected at load")
2. `FxRateCache.fromValetJson` with `"v":"0"`.

## Expected

Non-positive rates are rejected at load (a zero USD/CAD rate is corrupt data).

## Actual

Loads fine; `toCadInternal` returns 0 silently, which would zero out ACB and proceeds.
```
AssertionFailedError: v='0'
```

## Notes

Other malformed values tried (empty, abc, negative, comma, "1.", ".5", "1e0", leading space, missing v, null v, wrong series key) are all rejected. Coverage edges, 3/5 decimals and toCad overflow passed.
