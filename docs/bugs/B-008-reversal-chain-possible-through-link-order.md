# B-008 A reversal of a reversal can be committed by linking the rows in reverse order

**Story:** L-04 (AC7, placeholder C16) · **Severity:** minor · **Status:** Open · **Round:** 1

## Reproduction

1. Exact command(s) run:
   ```
   ./gradlew --init-script <mirror.init.gradle.kts> :tests:test -PincludeIntegration
   ```
2. Test: `L04ReversalDatabaseEdgeSpec` › "L-04 edge (C16): through the app role, a chain A <- R <- R2 cannot be built
   even by linking in reverse order". As `ledgerline_app`, in one transaction: insert entries R (lines mirror A) and
   R2 (lines mirror R); insert link `R2 -> R` first, then link `R -> A`; commit.

## Expected

The database refuses the commit. `V4__reversals.sql` says "an entry that is itself a reversal cannot be reversed
(C16, refused at the database too)", so no chain A <- R <- R2 may exist.

## Actual

```
AssertionError: a reversal of a reversal was committed (chain)
Expected value to not be null, but was null.   (the transaction committed)
```

`ledger.reversal_checks` only looks at whether `NEW.original_entry_id` is already a reversal. When `R2 -> R` is
written first, R is not yet a reversal, and when `R -> A` is written later nobody checks whether `NEW.reversal_entry_id`
is already someone's original.

## Notes

`LedgerRepository` cannot produce this order, so it needs a caller that writes SQL directly with the app role. Minor
for that reason, but the V4 header promises the database enforces C16. Fix: in `reversal_checks` also refuse when
`EXISTS (SELECT 1 FROM ledger.reversal r WHERE r.original_entry_id = NEW.reversal_entry_id)`.
