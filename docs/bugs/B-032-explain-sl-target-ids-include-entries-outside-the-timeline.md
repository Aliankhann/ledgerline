# B-032 Superficial-loss target entry ids can name a TFSA buy that is not a timeline step

**Story:** L-17 (AC3, AC6) · **Severity:** minor · **Status:** Open · **Round:** 1

## Reproduction

1. Exact command(s) run: same as B-031.
   Test: "L-17 attack 3: a repurchase in a TFSA never cites an entry that is not a timeline step".
2. Input (SYN-ATK): BUY 100 non-registered 2025-01-02 (entry 1001), SELL 100 at a loss 2025-07-01 (1002), BUY 10
   non-registered 2025-07-10 (1003), BUY 10 in a TFSA 2025-07-12 (1004).

## Expected

Every `superficialLoss.targetEntryIds` entry is a step the panel can highlight (AC6: selecting an entry id highlights
that step), or the story says registered targets are listed separately.

## Actual

```
Collection should contain all of [1003L, 1004L] but was missing [1004L]
```

## Notes

- `HoldingExplanation.superficial` maps every `SlTrigger` to its entry id; the matcher also records registered-account buys as
  triggers (`SL-REGISTERED`). The TFSA buy is not part of the non-registered pool, so it has no step. Whether to show it
  is an owner/BA question (the denial is real, the target is just not in this pool); the UI should at least not offer a
  chip that selects nothing.
