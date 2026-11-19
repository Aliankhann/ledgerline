# B-012 A NUL character in a free-text field is a 500 on POST and aborts the whole CSV import

**Story:** L-02 (AC8, C23) with L-01 · **Severity:** major · **Status:** Open · **Round:** 1

## Reproduction

1. Command (Docker; the first failing run was observed 2026-11-19 before the daemon stopped):
   ```
   ./gradlew --init-script <mirror.init.gradle.kts> :tests:test -PincludeIntegration --tests 'dev.ledgerline.spec.L02AttackLedgerSpec'
   ```
   Test: `L02AttackLedgerSpec` "L-02 attack (B-012): free-text NUL in a CSV row is rejected by line and the rest of
   the file still imports". File: four BUY rows; row 2 has `description` `a<NUL>b`, row 3 has `account_id` `AC<NUL>`.
2. HTTP: `L02AttackHttpSpec` "L-02 attack (B-012): NUL in a free-text field is a 4xx with a code and writes nothing"
   (`POST /v1/activities` with `"accountId":"X\u0000Y"`, same for `taxpayerId`, `securityId`, `description`). The
   earlier version of this test failed on the `accountId` case; the other three fields were not reached, so they are
   unconfirmed.

## Expected

L-01 accepts or refuses every text field with a named code. A NUL (or any character Postgres `text` cannot store) is
a 422 on `POST /v1/activities` and a rejection by line in `/v1/imports` (C23: good rows post, bad rows are
reported). Nothing is written for the bad row, and the rows after it still post.

## Actual

```
io.kotest.assertions.AssertionFailedError: import threw org.postgresql.util.PSQLException:
  ERROR: invalid byte sequence for encoding "UTF8": 0x00   Where: unnamed portal parameter $19
```
(CSV, `description`.) For the HTTP `accountId` case:
`java.sql.BatchUpdateException: Batch entry 0 INSERT INTO ledger.line ... was aborted: ERROR: invalid byte sequence
for encoding "UTF8": 0x00 Where: unnamed portal parameter $3` (the test host rethrows; a real server answers 500).

`IngestionService.importCsv` has no handler for `SQLException`, so the exception leaves the loop: rows before the bad
row are posted, rows after it never are, and the caller gets no report (HTTP 500). A re-upload posts nothing new
(first rows are duplicates) and fails again at the same row, so a file with one such row can never be imported in full
and the user cannot see which line is at fault.

## Notes

Root cause: L-01's `toActivity` validates the key, enums, dates and numbers but not the characters of `taxpayer_id`,
`account_id`, `security_id` or `description`. Cheapest fix is a validation rule in `CanonicalCsv` (new code, e.g.
`INVALID_CHARACTER`); a second line of defence is mapping `SQLException` in `importCsv` and the route to a per-row
rejection or a 4xx. Not rolled back wrongly: the failing statement's transaction rolls back, so no partial entry is
stored (checked by the service-level rollback tests, which pass).
