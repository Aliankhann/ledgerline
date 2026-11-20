# B-022 A buy commission difference is explained as POOL-IDENTICAL, not COST-BUY-COMMISSION

**Story:** L-11 · **Severity:** major · **Status:** Open · **Round:** 1

## Reproduction

1. `./gradlew :tests:test --tests '*L11AttackSpec*'` (test "a buy commission that the book value omits ...").
2. One buy, gross 225000 cents, commission 495; book value 225000.

## Expected

A reason with ruleId `COST-BUY-COMMISSION`, amount 495, naming the buy entry.

## Actual

The 495 is reported under `POOL-IDENTICAL` ("book value follows a different method").
```
NoSuchElementException: Collection contains no element matching the predicate.
```

## Notes

FX is not tested: the pool has no FX step (`AcbEngine` takes CAD amounts), so there is no FX reason to expect yet.
