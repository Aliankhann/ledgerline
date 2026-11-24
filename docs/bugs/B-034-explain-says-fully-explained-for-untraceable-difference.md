# B-034 "Fully explained" is true when the only reason is NO-LEDGER-HISTORY ("cannot be traced")

**Story:** L-17 (AC8) · **Severity:** major (the footer is the honesty gate of the story) · **Status:** Fixed (QE verified, round 2; provisional reading, owner to confirm) · **Round:** 1

## Reproduction

1. Exact command(s) run: same as B-031.
   Test: "L-17 attack 9: a sell left behind by a reversed buy is a data gap with no steps, and the sum rule holds".
2. Input: the effective activity set after the buy was reversed: one SELL of 10 SYN-ATK (entry 1002); book value 1000.00.
   `HoldingExplanation.explain(listOf(sell), 100_000, key)`.

## Expected

The difference is labelled "cannot be traced", so it is not "fully explained": `fullyExplained = false`, no
"Difference fully explained by 1 adjustments" footer.

## Actual

```
Explanation(... acbCents=0, differenceCents=-100000, steps=[],
 reasons=[Reason(ruleId=NO-LEDGER-HISTORY, text=The ledger has no entries for this holding, so the whole difference cannot be traced.,
 entryIds=[], amountMinor=-100000), Reason(ruleId=DATA-GAP-OVERSELL, entryIds=[1002], amountMinor=0)],
 status=DATA_GAP, fullyExplained=true, adjustmentCount=1, unexplainedCents=0)
```

## Notes

- Follows the literal AC8 text (cent reasons sum to the difference, no `UNEXPLAINED`), but a reason with no entry ids
  explains nothing. The same applies to the `DATA_GAP` reason for a book value with no activity. Needs an owner decision on
  whether these pseudo reasons count towards "fully explained"; QE's reading is that they must not.

## Round 2 verification

`fullyExplained` is false when a non-zero reason cites no entry; attack test 9 passes. Provisional: owner to confirm.
