# B-035 One malformed book value row makes every explain request fail with an exception

**Story:** L-17 (route) · shares the parser with L-11 · **Severity:** minor · **Status:** Open · **Round:** 1

## Reproduction

1. Exact command(s) run: same as B-031.
   Test: "L-17 attack 8: a malformed book value row for another security does not break this holding's explain".
2. CSV: header, `SYN-ATK,10.00`, then a bad row for SYN-OTHER. The three bad rows tried: `SYN-OTHER,not-money`,
   `SYN-OTHER,99999999999999999999.00`, and `SYN-OTHER` twice (duplicate). Request the valid holding SYN-ATK.

## Expected

A client error (4xx with the bad row named), or the valid row used. Not an unhandled exception (500) for a holding whose
own row is fine.

## Actual

```
SYN-OTHER,not-money -> EXCEPTION: java.lang.IllegalArgumentException: Malformed book value: not-money
```

The test stops at the first failing row; the overflow row (`toLong` / `multiplyExact`) and the duplicate row take the same
`BookValueCsv.parse` path, which throws `NumberFormatException`, `ArithmeticException` and `IllegalArgumentException`; QE did not
observe the last two separately.

## Notes

- The route calls `BookValueCsv.parse(taxpayer, bookValueCsv)` on the whole file for every request, with no handler.
  The L-11 route has the same shape; the book value file is service config, so impact is small.
