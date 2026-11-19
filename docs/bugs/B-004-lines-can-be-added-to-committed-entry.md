# B-004 The app role can INSERT extra lines into an already committed entry, unbalancing it

**Story:** L-03 (AC5, AC9; invariants 1 and 3) · **Severity:** minor · **Status:** Fixed · **Round:** 1

## Reproduction

1. Exact command(s) run:
   ```
   ./gradlew --init-script <mirror.init.gradle.kts> :tests:test -PincludeIntegration
   ```
2. Test: `L03ImmutabilityEdgeSpec` › "L-03 edge (B-004): as ledgerline_app, a line added to an already committed
   entry is refused". Post AC1 through `LedgerRepository`, then as `ledgerline_app` (`SET ROLE`) run:
   ```
   INSERT INTO ledger.line (entry_id, line_no, account, unit, side, amount)
   VALUES (1, 99, 'TP-0001-NR1', 'CAD', 'DEBIT', 1000000)
   ```

## Expected

A committed entry is closed: its line set can never change, and the database never holds an entry that does not
balance per unit. The insert fails; the ledger hash is unchanged.

## Actual

```
java.lang.AssertionError: as ledgerline_app: INSERT INTO ledger.line (entry_id, line_no, account, unit, side, amount) VALUES (1, 99, 'TP-0001-NR1', 'CAD', 'DEBIT', 1000000)
Expected value to not be null, but was null.
```

The insert succeeds. Entry 1 now has an extra CAD debit of 1000000 with no credit: it is unbalanced, and what AC1's
entry says has changed without any UPDATE, so the append-only trigger and the AC9 snapshot check (rows only added)
both miss it. Balance is enforced only in Kotlin (`Posting.requireBalanced`), before the write.

## Notes

- Any future code path that writes `ledger.line` with a wrong `entry_id` (for example L-04 reversals) would do this
  silently. Options: a deferred constraint trigger that checks per-unit balance of each touched entry at commit, and
  refuses lines for an entry created in an earlier transaction; or write entry and lines through one function.
- Related: the app role can also commit an entry row with no lines. Not asserted here because B-006 may decide that
  zero-amount activities post as empty entries.

## Fix note

Round 2: migration V3 seals entries: a line must share its entry's transaction (`xmin`), lines append in `line_id`
order, and deferred constraint triggers require at commit that every entry has a line and balances per unit.
