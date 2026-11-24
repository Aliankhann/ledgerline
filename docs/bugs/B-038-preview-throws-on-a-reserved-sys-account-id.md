# B-038 Preview throws (500) on a row whose account_id starts with `SYS:`; confirm and import post part of the file, then 500

**Story:** L-20 (AC3 "rejected rows carry line and reason", AC6 confirm, AC5 preview) · **Severity:** major · **Status:** Open · **Round:** 1

## Reproduction

1. Exact command(s) run (worktree `main-wt`, branch `l-20`, HEAD 6f76710):
   ```
   ./gradlew --init-script ../mirror.init.gradle.kts :tests:specTest --tests '*L20Attack*' --offline
   ```
   Test: "L-20 attack 6a: an account id the ledger reserves (SYS:) is a REJECTED row, never an exception".
2. The input is a canonical file with a good BUY on line 2 and, on line 3, a BUY with `account_id` `SYS:MARKET`. The CSV
   parser accepts the row (L-01 has no rule for the prefix).

## Expected

Line 2 `NEW`, line 3 `REJECTED` with a code, field and message (the story's contract: a row the ledger refuses is
reported with its line and reason, "never silently clipped" and never a crashed preview). Confirm posts line 2 and
reports line 3.

## Actual

```
classify threw java.lang.IllegalArgumentException: S-1: accountId uses the system prefix
```

Origin: `Posting.linesFor` (`ledger/Posting.kt:50`, `require(!a.accountId.startsWith(SYSTEM_ACCOUNT_PREFIX))`).
`ImportPreview.refusal` (`app/ImportPreview.kt`) and `IngestionService.submit` catch only `ZeroAmountException` (and
`EventTooLargeException` in `submit`); the `IllegalArgumentException` escapes. By code reading (not run, no Postgres
here): `POST /v1/imports/preview` answers 500 for the whole file, and `POST /v1/imports/confirm` (and the existing
`POST /v1/imports`) post every row above the bad line, then answer 500 with no report, so the client cannot tell what
was posted. A retry is safe (idempotent) but fails again on the same row.

## Notes

- The throw in `importCsv` pre-dates L-20, but L-20 copies the "refusal" logic and adds a preview that is meant to
  predict it, so the preview is where users meet it first.
- Fix direction (Dev's call): catch `IllegalArgumentException` from `Posting.linesFor` in both places as a refusal
  with a code and field `account_id`, or reject the prefix in the parser (L-01 change, needs the owner's view).
  Whatever is chosen, preview, confirm and `POST /v1/imports` must agree.
- Related and not filed: the same `Posting.linesFor` also throws on a blank account id, and `Posting.requireBalanced`
  can throw `ArithmeticException`; the parser's 18-digit cap makes both unreachable from CSV today.
