# B-031 Explain timeline is not ordered by tax date when a superficial-loss target precedes the sale

**Story:** L-17 (AC5, AC3) · **Severity:** minor (wrong order in the timeline; sums are still exact) · **Status:** Fixed (QE verified, round 2; provisional reading, owner to confirm) · **Round:** 1

## Reproduction

1. Exact command(s) run:
   ```
   ./gradlew --init-script ../mirror.init.gradle.kts :tests:specTest --tests '*L17Attack*' --offline   (from the worktree root, branch l-17, HEAD 0a40965)
   ```
   Test: "L-17 attack 1: an add-back whose target precedes the sale keeps the steps ordered by tax date (AC5)".
2. Input (one non-registered pool SYN-ATK): BUY 100 on 2025-01-02, BUY 10 on 2025-06-20, SELL 100 on 2025-07-01 at a loss.
   The only substituted purchase (the 2025-06-20 buy) is before the sale; 10 shares are still held. The test first
   checks that the sale has `denied > 0`. Then `HoldingExplanation.explain(rows, null, key)`.

## Expected

AC5: "Steps are ordered by tax date, then sequence." Tax dates of the steps are non-decreasing.

## Actual

```
Element differ at index: [2, 3]
expected:<[2025-01-02, 2025-06-20, 2025-06-20, 2025-07-01]> but was:<[2025-01-02, 2025-06-20, 2025-07-01, 2025-06-20]>
```

## Notes

- Cause (from reading `SuperficialReplay.kt`, not changed): the add-back is applied at `max(saleIndex, target.index)`, so it
  follows the sale in history, but its `PoolChange.taxDate` is the target's date (the story says so: "with the TARGET's
  tax date"). `HoldingExplanation.steps` keeps history order, so the add-back step has an earlier date than the step before it.
- The story contradicts itself here (AC5 order by tax date vs. the add-back carries the target's date). The owner or BA
  must say which wins: either the add-back step is dated at the sale (history order) or the timeline is sorted and the
  running totals recomputed. Not silently changed by QE.

## Round 2 verification

Dev sorts steps by tax date (stable) and recomputes running totals and cost deltas; attack tests 1, 2, 4 and 10 pass and
cost deltas still sum to ACB. Provisional owner decision (AC5 order wins over history order): owner to confirm.
