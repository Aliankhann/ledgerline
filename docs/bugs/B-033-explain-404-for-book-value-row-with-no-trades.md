# B-033 Drill-down 404s for a holding the ACB-vs-book list shows

**Story:** L-17 (AC2 exemption, AC4) · **Severity:** minor · **Status:** Open · **Round:** 1

## Reproduction

1. Exact command(s) run: same as B-031.
   Test: "L-17 attack 7: a book value row for a security with no trades is explained, not 404, if the list shows it".
2. Input: taxpayer TP-0001 with a BUY of SYN-ATK; book value CSV has `SYN-NOTRADES,50.00`.
   `BookValueComparison.compareAll` lists SYN-NOTRADES (reason `DATA_GAP` / `NO-LEDGER-HISTORY`, no entry ids), which the test
   asserts first. Then `GET /v1/holdings/TP-0001/SYN-NOTRADES/explain`.

## Expected

The row the explorer shows can be opened. AC2 exempts `NO_LEDGER_HISTORY` and `DATA_GAP` from the entry-id rule "by
design", which only makes sense if explain can return them. 200 with empty steps and status DATA_GAP.

## Actual

```
No holding SYN-NOTRADES for taxpayer TP-0001
expected:<200 OK> but was:<404 Not Found>
```

## Notes

- `HoldingExplanation.explain` returns null when there is no pool and no L-16 warning, ignoring the book value. AC4 says an
  unknown security is 404, so this is an AC4 vs AC2 conflict for a security that is unknown to the ledger but known to
  the broker. Owner/BA to decide; the current behaviour makes the DATA_GAP reason unreachable here.
