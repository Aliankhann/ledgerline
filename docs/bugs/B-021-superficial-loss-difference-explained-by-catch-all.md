# B-021 A superficial-loss add-back is explained as POOL-IDENTICAL, not an SL-* rule

**Story:** L-11 · **Severity:** major · **Status:** Open · **Round:** 1

## Reproduction

1. `./gradlew :tests:test --tests '*L11AttackSpec*'` (test "an ACB raised by a superficial loss add-back ...").
2. History: cited fixture `acb-slr-example1` rows t1..t3 (sell at a loss, repurchase next day). Pool from
   `replaySuperficial`; book value from the plain `replay` (no add-back).

## Expected

The difference (the fixture's denied loss, 200000 cents) is explained by an `SL-*` reason (the add-back, entry of the sale).

## Actual

`BookValueComparison.reasons` only knows `ROC-REDUCES-ACB`; the rest goes to `POOL-IDENTICAL` with text saying the
broker "follows a different method". The user is shown a false explanation.
```
AssertionFailedError: expected:<200000L> but was:<0L>
```

## Notes

Dev already lists SL reasons as not done. Same cause for B-022.
