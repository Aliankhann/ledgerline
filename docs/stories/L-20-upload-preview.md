# L-20 Upload preview: dry-run import before posting

**Status:** Draft  <!-- Draft → Approved → Tests written → In dev → In QE → Done. Only the owner sets Approved. -->
**Day:** N · **Branch:** `l-20` · **Round:** 0 / 3

## Story

As a _self-directed investor_, I want to upload my activity CSV and first see what the import would do (how many rows
are new, which are duplicates, which conflict or are rejected and why, and which data gaps it would cause) before
anything is posted, so that I can fix the file or stop, and then confirm to post exactly what I saw. As a _reviewing
engineer_, I want the preview to be provably read-only and the confirm to reuse the existing idempotent ingestion path,
so that uploading the same file twice, or next year's overlapping file, can never double-count.

## Why it matters

Overlapping exports create duplicates that an ACB tool must flag (E-30), and the same event submitted twice must be a
no-op while the same key with a different payload is an error (E-17); see
[`docs/problem/evidence.md`](../problem/evidence.md). L-02 makes the re-upload safe after the fact; this story lets the
user see it first. Evidence that users want a preview step specifically is thin: the BA found no sourced user report and
does not invent one. The story involves no tax values; every expected value is a count, a line number, a code or an id.
Extra story beyond the 14-story cap; owner approval needed.

## Code facts this story reuses (no new tax logic)

- Parser: `CanonicalCsv` in `ingest/` (L-01): per-row `Rejection` (line, code, field, message) and file-level `fileErrors`
  (`HEADER_INVALID`). `importCsv` sets `sourceSeq` to the physical line; `sourceSeq` and `description` are not hashed
  (L-02 change notes, C20 amended).
- Ingestion: `IngestionService.importCsv` and `POST /v1/imports` (`app/IngestionRoutes.kt:96-135`) answer
  `{posted, duplicates, rejected[], fileErrors[]}`. Same hash is a duplicate; same key with another hash is
  `IDEMPOTENCY_KEY_MISMATCH` (the code the tests use; the L-02 story text says `..._REUSED`). Body caps today: 32 MB
  import, `413 PAYLOAD_TOO_LARGE` (`IngestionRoutes.kt:139-157`).
- Warnings: `DataGapWarnings.detect(activities, replay)` (L-16): `DATA-GAP-TRANSFER-NO-COST`, `DATA-GAP-OVERSELL`,
  `amountMinor` 0, entry ids. A not-yet-posted row has no entry id, so the preview names the file line instead.
- Existing UI pattern: `ui/src/App.tsx` tabs, `ui/src/AcbVsBook.tsx`, `ui/src/YourYear.tsx` (L-18).

## Acceptance criteria

Endpoint paths and field names are proposals (see decisions). `L-01 AC1 file` and `L-01 AC2 file` mean the files given in
those stories. Row status is one of `NEW`, `DUPLICATE`, `CONFLICT`, `REJECTED`. Counts always satisfy
`rowsRead = new + duplicates + conflicts + rejected`.

1. **AC1 Preview of a fresh file posts nothing.**
   - Input: empty ledger; `POST /v1/imports/preview` with the L-01 AC1 file (3 rows).
   - Expected: `200`; `rowsRead 3`, `new 3`, `duplicates 0`, `conflicts 0`, `rejected 0`, `warnings []`; `rows` lists lines
     2, 3, 4 with keys `TP-0001-2025-0001`, `-0002`, `-0003`, each `NEW`. Ledger entries, activities and outbox rows: 0.
2. **AC2 Re-upload and overlapping next-year file are previewed as duplicates, never as new.**
   - Input: after the L-01 AC1 file is imported (3 entries), preview (a) the same file, and (b) a file with the AC1 header,
     the first two AC1 data lines unchanged, and one new line with key `TP-0001-2026-0001`, `BUY`, 2026-01-06, `SYN-AAA`,
     10, CAD, 100.00.
   - Expected: (a) `rowsRead 3`, `new 0`, `duplicates 3`. (b) `rowsRead 3`, `new 1`, `duplicates 2`; line 4 is `NEW`, lines
     2 and 3 `DUPLICATE`. A row that differs from its stored twin only in `description` is a `DUPLICATE` (L-02 C20). Ledger
     still has exactly 3 entries. In-file repeats follow L-02: the same key and payload twice in one file gives one `NEW`
     and one `DUPLICATE`; the same key with a different payload gives `NEW` then `CONFLICT` (OWNER decision below).
3. **AC3 Conflicts, rejected rows and bad files carry line and reason.**
   - Input: after AC2's import, preview (a) the AC1 file with line 3's `amount` changed to `3021.50`; (b) the L-01 AC2
     file; (c) the AC1 file with the `commission` header removed; (d) a body larger than the cap.
   - Expected: (a) `conflicts 1`, `duplicates 2`, row line 3 `CONFLICT` with code `IDEMPOTENCY_KEY_MISMATCH`, no stored
     value changed. (b) `rowsRead 9`, `new 1` (line 10), `rejected 8` with exactly the L-01 AC2 table (lines 2 to 9 and
     codes `AMOUNT_TOO_PRECISE`, `INVALID_DATE`, `UNKNOWN_ACTIVITY_TYPE`, `UNSUPPORTED_CURRENCY`, `MISSING_FIELD`,
     `NON_POSITIVE_QUANTITY`, `SETTLEMENT_BEFORE_EFFECTIVE`, `QUANTITY_TOO_PRECISE`), each with `field` and `message`.
     (c) `rowsRead 0`, one `fileErrors` entry `HEADER_INVALID` naming `commission`, and the confirm action is not offered.
     (d) `413 PAYLOAD_TOO_LARGE`, nothing parsed. Zero-byte file: `HEADER_INVALID`; header-only file: all counts 0.
4. **AC4 Data gaps the import would cause are listed with their file lines.**
   - Input: empty ledger; file with line 2 `SELL` 5 `SYN-AAA` (no earlier buy) and line 3 `TRANSFER_IN` 100 `SYN-BBB`.
   - Expected: `new 2`; `warnings` has `DATA-GAP-OVERSELL` for `SYN-AAA` with `line 2`, and `DATA-GAP-TRANSFER-NO-COST` for
     `SYN-BBB` with `line 3`, each `amountMinor 0`. Warnings already present in the ledger before the file are not
     repeated as caused by the file (OWNER decision below). After a clean file (L-16 AC4 data: `BUY` 10, `SELL` 4) the
     list is empty. The preview computes this in memory; nothing is stored.
5. **AC5 The preview is read-only (structural).**
   - Input: ledger with the AC1 file imported; record row counts of every ledger table (entries, lines, activities,
     outbox) and the replay output hash; then run previews of the AC1 file, the AC3 files and the AC4 file, including 20
     concurrent previews.
   - Expected: every count and the hash are identical afterwards; no outbox row is created; a key seen only in a
     preview is later accepted as `NEW` (a preview consumes no key). The preview path holds no write handle to the
     ledger: it runs on a read-only transaction or connection, and a test that makes any insert fail the preview
     proves it (QE chooses the technique). Two previews of the same file at the same ledger state return byte-identical
     bodies (invariant 5).
6. **AC6 Confirm posts exactly the previewed new rows, once.**
   - Input: ledger holds the L-01 AC1 file (3 entries). Upload a file with the AC1 header and 4 data lines: line 2 =
     AC1 line 2 unchanged; line 3 = AC1 line 3 with `amount` `3021.50`; line 4 = a new valid row with key
     `TP-0001-2025-0020`; line 5 = a row with `currency` `EUR`. Preview it, then confirm with the same file and the
     preview's `fileSha256`.
   - Expected: preview says `rowsRead 4`, `new 1` (line 4), `duplicates 1` (line 2), `conflicts 1` (line 3), `rejected 1`
     (line 5, `UNSUPPORTED_CURRENCY`). Confirm answers `posted 1`, `duplicates 1`, the same conflict and rejected rows,
     matching the preview row for row; the ledger gains
     exactly 1 entry and 1 outbox row (key `TP-0001-2025-0020`); the conflicting and rejected rows post nothing. Confirm
     with a different file body than the previewed `fileSha256` answers `409 PREVIEW_MISMATCH` and posts nothing. A
     confirm with no `fileSha256` is `400`. Confirm calls the same ingestion path as `POST /v1/imports` (no second
     implementation of the idempotency rule); `POST /v1/imports` keeps its L-02 behaviour unchanged.
7. **AC7 If the ledger changed since the preview, confirm never double-posts.**
   - Input: preview the AC1 file on an empty ledger (`new 3`). Before confirming: (a) another client posts AC1's second
     row through `POST /v1/activities` with the same payload; then confirm. Separately (b) another client posts the third
     key with `amount` `1400.00` (different payload); then confirm. Separately (c) two confirms of the same file run
     concurrently on an empty ledger.
   - Expected: (a) confirm `posted 2`, `duplicates 1`; the ledger has exactly 3 entries, one per key. (b) `posted 2`,
     `conflicts 1` for line 4; the other client's `1400.00` entry is untouched, nothing overwritten. (c) one confirm
     reports `posted 3`, the other `posted 0, duplicates 3`; exactly 3 entries and 3 outbox rows. Whenever confirm differs
     from the preview, the response says so (`changedSincePreview true`) and lists the rows whose status moved.
8. **AC8 UI "Upload" tab.**
   - Input: the app with the Upload tab, driven by a component test or typecheck plus build (QE chooses the tool).
   - Expected: a file picker, then a preview with count cards (Rows read, New, Duplicates skipped, Conflicts,
     Rejected), a table (line, key, type, date, status, reason; rejected and conflict rows first or highlighted, not
     hidden), and a Warnings list showing the data-gap rule id, file line and text. The "Confirm import" button is
     disabled until a preview has loaded, when `new` is 0, when there is a `fileErrors` entry, and while a request is in
     flight, so a double click sends one confirm. Choosing a different file clears the preview and disables confirm. The
     final screen shows the confirm result counts, and an Upload of the same file again shows `new 0`. Counts use integer
     text only. Passes `npm run typecheck && npm run build`.

## Out of scope

- Broker detection or mapping beyond the canonical CSV (L-19 later). Wealthsimple or other formats are not read here.
- Editing or fixing rows in the browser; the user fixes the file and uploads again.
- Reversals, corrections, deleting an import ("undo import"); L-04 owns reversals.
- Saving a preview on the server, import history, or a staging table (see token decision).
- Streaming very large files, pagination of the row table beyond the cap in the decisions, progress bars.
- Any new tax rule, any change to calculation, ACB values shown in the preview, FX conversion, or real broker APIs.
- Authentication and per-user file ownership. Synthetic data and the owner's own CSV only.

## Needs owner decision

- **Preview token versus stateless confirm (design).** Proposal: stateless. Confirm re-sends the same file plus its
  `fileSha256` from the preview; the server re-evaluates against the current ledger and relies on idempotency, so no
  server-side preview state or table exists. Alternative: the server stores the preview and returns an id; that needs
  a table or cache, expiry, and an ADR, and still cannot prevent a changed ledger (AC7). BA recommends stateless; the
  choice is yours and goes in an ADR.
- **Maximum file size.** Today the import cap is 32 MB (`MAX_IMPORT_BYTES`). Does the preview use the same cap, or a
  lower one (for example 5 MB) because it also returns a row list? Also a cap on rows in the `rows` response (for
  example the first 1,000 plus all non-`NEW` rows). The BA has no sourced figure; the Wealthsimple 1,000-activity
  export cap noted in L-01 applies to the real export only.
- **Partial confirm (also L-01 and L-02).** AC6 assumes good rows post while bad rows are reported. If you choose
  all-or-nothing, confirm would refuse the whole file when any row is rejected or in conflict, and AC6 and AC7 change.
- **Duplicates inside one file.** L-02's proposal (one `NEW`, one `DUPLICATE`; different payload gives `CONFLICT`) is
  assumed in AC2. If you decide differently there, AC2 follows.
- **Which warnings count as "caused by the import" (AC4).** Proposal: only warnings that exist after the hypothetical
  import and did not exist before, matched by security and rule id. Alternative: show all warnings for the affected
  securities. The in-memory replay of existing plus new rows is also a performance cost for big ledgers (no benchmark
  yet).
- **Conflict code name.** L-02's text says `IDEMPOTENCY_KEY_REUSED`; the code and tests use `IDEMPOTENCY_KEY_MISMATCH`.
  This story follows the code. Confirm.
- **Endpoint shape.** Paths `POST /v1/imports/preview` and `POST /v1/imports/confirm`, the `fileSha256` field, the
  `PREVIEW_MISMATCH` code and the `changedSincePreview` flag are BA proposals.
- **Read-only enforcement (AC5).** Is a read-only database transaction or separate read-only role acceptable, or is a
  code-level guarantee enough? Needs your view on whether a new database role is worth it.
- **Extra story beyond the 14-story cap.** Needs your explicit approval, as for L-16 to L-18.
- No tax judgment is made in this story.

## Test map (QE fills)

| AC | Test |
| --- | --- |
| AC1 | `tests/spec/.../L20Spec.kt` › "L-20 AC1: ..." |

## Change notes (Dev fills)

- _None yet._
