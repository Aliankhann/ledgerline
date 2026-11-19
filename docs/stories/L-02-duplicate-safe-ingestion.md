# L-02 Duplicate-safe ingestion

**Status:** In QE  <!-- Draft → Approved → Tests written → In dev → In QE → Done. Only the owner sets Approved. -->
**Day:** 4 · **Branch:** `L-02-duplicate-safe-ingestion` · **Round:** 0 / 3

## Story

As a self-directed investor, I want re-uploading the same activity or the same CSV to change nothing, and a changed
activity under an old key to be refused with a clear error, so that retries and double uploads can never corrupt my
cost basis.

## Why it matters

Duplicate-safe submission is the core trust property of payment and brokerage ledgers, and duplicate uploads are a
common way to corrupt a spreadsheet ACB. See [`docs/problem/evidence.md`](../problem/evidence.md): E-17
(Wealthsimple's Book of Record: "The same event submitted twice is a no-op; the same identifier with a different
payload is a validation error") and E-30 (overlapping Wealthsimple exports create duplicates that an ACB tool must
flag). Not sourced: that duplicate imports corrupt users' records in practice (no user report found).

## Notes for QE and Dev

- Invariant covered: **2 Idempotency**. Design per ADR 0001: a unique key plus a hash of the canonical payload.
- Builds on L-01 (canonical activity and validation) and L-03 (posting). Outbox rows are L-05, but this story asserts
  that a duplicate creates no outbox row either.
- **Interface proposal (Dev may refine; QE tests the version the owner approves):**
  - `POST /v1/activities` with a JSON canonical activity. Amounts and quantities are decimal **strings**, so no JSON
    floating-point number is ever parsed.
  - `201 Created` on first post: `{"activityId": "...", "entryId": "...", "status": "POSTED"}`.
  - `200 OK` on an identical repeat: same body as the original with `"status": "DUPLICATE"`.
  - `409 Conflict` on the same key with a different payload: `{"code": "IDEMPOTENCY_KEY_REUSED", "idempotencyKey": "..."}`.
  - `422 Unprocessable Entity` on validation failure: `{"code": "<reason>", "field": "..."}`, same codes as L-01.
  - `POST /v1/imports` with a canonical CSV body: `200 OK` with `{"posted": n, "duplicates": n, "rejected": [...]}`.
- Base activity used below, called **A1**:
  ```json
  {"idempotencyKey": "TP-0001-2025-0002", "taxpayerId": "TP-0001", "accountId": "TP-0001-NR1",
   "accountType": "NON_REGISTERED", "activityType": "BUY", "effectiveDate": "2025-01-07",
   "settlementDate": "2025-01-08", "securityId": "SYN-AAA", "quantity": "100", "currency": "CAD",
   "amount": "3012.50", "commission": "4.95"}
  ```

## Acceptance criteria

1. **AC1 First submission posts.**
   - Input: `POST /v1/activities` with A1 on an empty ledger.
   - Expected: `201`, `status=POSTED`; exactly 1 ledger entry and 1 outbox row for key `TP-0001-2025-0002`.
2. **AC2 Identical resubmission is a no-op.**
   - Input: A1 again, byte for byte.
   - Expected: `200`, `status=DUPLICATE`, same `activityId` and `entryId` as AC1; ledger entry count, line count and
     outbox row count unchanged; CAD cash balance of `TP-0001-NR1` unchanged.
3. **AC3 Same key with a changed payload is rejected.**
   - Input: A1 with `"amount": "3021.50"` (one digit swapped).
   - Expected: `409`, `code=IDEMPOTENCY_KEY_REUSED`, `idempotencyKey=TP-0001-2025-0002`; no new rows anywhere; a
     `GET` of the activity still shows `amount` `3012.50`.
4. **AC4 Equivalent encodings are the same payload.**
   - Input: A1 with (a) the JSON fields in reverse order, (b) extra whitespace between tokens, (c) `"amount": "3012.5"`,
     (d) `"quantity": "100.000000"`.
   - Expected: each returns `200` `DUPLICATE`. The payload hash is computed over the canonical activity (parsed
     integers in a fixed field order), not over the raw request bytes.
5. **AC5 System-set fields do not affect the hash.**
   - Input: A1 submitted at 2025-01-07T15:00:00Z and again at 2025-01-08T09:30:00Z.
   - Expected: second call is `200` `DUPLICATE`; the stored `recorded_at` is the first call's time.
6. **AC6 Concurrent identical submissions post once.**
   - Input: 20 concurrent `POST /v1/activities` with A1 on an empty ledger.
   - Expected: exactly one `201` and nineteen `200 DUPLICATE`; exactly 1 ledger entry and 1 outbox row.
7. **AC7 Concurrent conflicting submissions: one wins, the rest are refused.**
   - Input: 10 concurrent posts with key `TP-0001-2025-0002`, each with a different `amount` (`3012.50`, `3012.51`, …,
     `3012.59`) on an empty ledger.
   - Expected: exactly one `201`; the other nine are `409 IDEMPOTENCY_KEY_REUSED`; the stored amount is the one from
     the `201` response; exactly 1 ledger entry.
8. **AC8 Invalid submissions are refused and do not consume the key (proposed default).**
   - Input: A1 with `"currency": "EUR"`, then A1 as given.
   - Expected: first call `422` with `code=UNSUPPORTED_CURRENCY`, `field=currency`, no rows written; second call
     `201 POSTED`.
9. **AC9 Uploading the same CSV twice changes nothing the second time.**
   - Input: the L-01 AC1 three-row file, sent twice to `POST /v1/imports`.
   - Expected: first response `{"posted": 3, "duplicates": 0, "rejected": []}`; second
     `{"posted": 0, "duplicates": 3, "rejected": []}`; ledger has exactly 3 entries.
10. **AC10 A CSV with a conflicting row reports it by line.**
    - Input: after AC9, upload the same file with line 3's `amount` changed from `3012.50` to `3021.50`.
    - Expected: `{"posted": 0, "duplicates": 2, "rejected": [{"line": 3, "code": "IDEMPOTENCY_KEY_REUSED"}]}`; ledger
      still has exactly 3 entries.
11. **AC11 Property test, invariant 2 (Idempotency).**
    - Input: generated activity streams where each activity is submitted 1 to 5 times in random order, at least 500
      cases.
    - Expected: the final ledger (entries, lines, balances) equals the ledger from submitting each distinct activity
      once. Any resubmission with a mutated payload is rejected. Test output reports the number of cases.

## Out of scope

- Publishing to Kafka and crash safety (L-05).
- Reversals and corrections (L-04); key reuse after reversal is decided there.
- Authentication and per-client key namespaces (out of project scope).
- Key expiry. Keys are kept forever.

## Needs owner decision

- **Which fields go in the payload hash** (ADR 0001). Proposal: every canonical field except system-set ones
  (`recorded_at`, assigned ids, sequence). Open: is `description` in the hash? If yes, fixing a typo in a description
  becomes a conflict; if no, a changed description is silently ignored.
- **Does a rejected (invalid) submission consume the key?** AC8 proposes no.
- **Response for a duplicate**: `200` with the original body (proposed) versus `201` replayed, or a header such as
  `Idempotent-Replayed: true`.
- **Partial CSV import** (also in L-01): AC10 assumes partial (good rows post, bad rows are reported). If the owner
  picks all-or-nothing, AC9 and AC10 change.
- **Duplicate keys inside one file**: same key and same payload twice in one file: count as one post plus one
  duplicate (proposed), or reject the file?

## Test map (QE fills)

Tests are provisional (story still Draft); placeholders C20 to C24 are in the KDoc of `L02IngestionSpec`. The API
contract (`IngestionService`, `SubmitResult`, `ImportReport`, `PayloadHash`, `ingestionModule`, `ledger.outbox`) is there
too. Differences from the story's interface proposal: error code `IDEMPOTENCY_KEY_MISMATCH` (not `..._REUSED`) and an
`alreadyExisted` flag instead of `status`. All database tests are tagged Integration (Docker).
S = `tests/spec/kotlin/dev/ledgerline/spec/`.

| AC | Test |
| --- | --- |
| AC1 | `S/L02IngestionSpec.kt` "L-02 AC1: first submission posts one entry and one outbox row"; HTTP "L-02 AC1: POST /v1/activities with A1 on an empty ledger is 201 ..." |
| AC2 | `S/L02IngestionSpec.kt` "L-02 AC2: identical resubmission is a no-op ..." (service) and "L-02 AC2: the same body again is 200 ..." (HTTP) |
| AC3 | `S/L02IngestionSpec.kt` "L-02 AC3: same key with amount 3021.50 ..." (service), "L-02 AC3: amount 3021.50 under the used key is 409 ..." (HTTP), "L-02 AC3 (C20): any hashed field changed is a mismatch; description alone is a duplicate" |
| AC4 | `S/L02IngestionSpec.kt` "L-02 AC4: reversed field order, extra whitespace, amount 3012.5 and quantity 100.000000 are all duplicates"; `S/L02ImportSpec.kt` "L-02 AC4 (C20): PayloadHash is 64 lowercase hex ..." |
| AC5 | `S/L02IngestionSpec.kt` "L-02 AC5: A1 resubmitted a day later ..." (service) and "L-02 AC5: A1 at 2025-01-07T15:00:00Z ..." (HTTP) |
| AC6 | `S/L02IngestionSpec.kt` "L-02 AC6: 32 threads ..." (service) and "L-02 AC6: 20 concurrent POSTs of A1 ..." (HTTP) |
| AC7 | `S/L02IngestionSpec.kt` "L-02 AC7: 16 threads ..." (service) and "L-02 AC7: 10 concurrent POSTs ..." (HTTP) |
| AC8 | `S/L02IngestionSpec.kt` "L-02 AC8: currency EUR is 422 UNSUPPORTED_CURRENCY ..." (HTTP) and "L-02 AC8 (C21): a refused zero-amount deposit ..." (service) |
| AC9 | `S/L02ImportSpec.kt` "L-02 AC9: the L-01 AC1 file twice ..." (HTTP), "L-02 AC9 and AC10: importCsv reports the same numbers through the service", "L-02 AC9 (C23) ...", "L-02 AC9 (C24) ..." |
| AC10 | `S/L02ImportSpec.kt` "L-02 AC10: after AC9, line 3 with amount 3021.50 ..." (HTTP) and the service test above |
| AC11 | `tests/property/kotlin/dev/ledgerline/property/IdempotencyInvariantPropertyTest.kt` "Invariant 2 (L-02 AC11) ..." (500 cases; prints the case count) |

Phase B attack tests (round 1), `S/L02AttackHttpSpec.kt`, `S/L02AttackLedgerSpec.kt`, `S/L02AttackHashSpec.kt`: lookalike
and over-long keys, malformed JSON, hostile text, number edges, crash injection at the activity and outbox inserts,
app-role privileges, concurrent imports, mismatch after a duplicate in one file, 10 000 rows, hash stability across
locale, time zone and JVM. Bugs: B-012, B-013, B-014 in `docs/bugs/`.

## Change notes (Dev fills)

- 2026-11-19, Dev (provisional build on placeholders C20 to C24 in `docs/owner-inputs.md`; marked `OWNER-INPUT: Cn`).

- **Funnel.** `LedgerRepository.submit` (ledger/) takes the append lock, looks up `ledger.activity` by key, and either
  returns the original ids (same hash), `Mismatch` (other hash), or inserts entry, lines, activity and outbox in one
  transaction. A 23505 is retried once and answered from the winner's row. `IngestionService` (app/) maps this to
  `SubmitResult`; `ZeroAmountException` becomes `Invalid(ZERO_AMOUNT)` and writes nothing (C21).
- **Hash (C20, amended).** `PayloadHash`: SHA-256 over a length-prefixed serialization of every Activity field except
  `description` and `sourceSeq` (`recorded_at` is not an activity field). `importCsv` sets `sourceSeq` to the row's
  physical line (C4 ordering) and it is not hashed, so C24 and re-uploads work.
- **Contradictions with amended C20 (for QE, tests untouched).** Three tests expect `sourceSeq` to be hashed and fail on
  that alone: `L02ImportSpec` "AC4 (C20) PayloadHash ..." (`sourceSeq = 7L`), `L02IngestionSpec` "AC3 (C20)" (the
  `sourceSeq` entry), and `IdempotencyInvariantPropertyTest` (the `sourceSeq + 1000` mutant from `IdempotencyPlans`).
  Everything else passes. Recorded in `docs/status.md`.
- **Outbox.** AC1, AC2 and AC6 assert one outbox row per new entry, so the table is required by the ACs (the story's
  note that outbox rows are L-05 conflicts with them; the ACs win). Minimal and append-only: `outbox_id`, `entry_id`,
  `activity_id`, no sent marker (delivery state is L-05, C5).
- **Migration V4** (`V4__activity_and_outbox.sql`): `ledger.activity`, `ledger.outbox`, append-only triggers, SELECT and
  INSERT only for `ledgerline_app`, sequence USAGE re-granted. No foreign keys, because the L-03 superuser TRUNCATE
  test needs "append-only" to fire before an FK error. **V-number clash:** L-04 is not on this branch; if it also adds
  a V4, one must be renumbered at merge.
- **HTTP.** `POST /v1/activities`, `GET /v1/activities/{key}`, `POST /v1/imports` in `IngestionRoutes.kt`, wired in
  `Main.kt`. JSON values are read as text and parsed with the L-01 rules (`CanonicalCsv.parseFields`), so no number
  becomes a double. The import response also carries `fileErrors`. `ParseResult` gained `activityLines`.
- **Build file.** Root `build.gradle.kts` opts the `:tests` project into `io.kotest.common.ExperimentalKotest`;
  without it `IdempotencyInvariantPropertyTest` fails to compile under warnings-as-errors.
- **Results.** `./gradlew ktlintFormat build --rerun-tasks`: compiles and lints clean; `:tests:test` 106 tests, 1 failed
  (the non-Docker PayloadHash test), 49 skipped. `:tests:test -PincludeIntegration`: 106 tests, 3 failed (the three above),
  1 skipped, 102 passed.

- 2026-11-19, Dev, round 2 (B-012, B-013, B-014; Fixed, pending integration verification, Docker not available).
  `CanonicalCsv` refuses NUL and lone surrogates in `taxpayer_id`, `account_id`, `security_id` and `description` with
  `INVALID_CHARACTER` (422 over HTTP, a per-row rejection in CSV import, nothing reaches the DB). `GET
  /v1/activities/{key}` answers 404 for a key that breaks the key pattern without querying. `POST` bodies are capped
  (4 MB activity, 32 MB import, 413 `PAYLOAD_TOO_LARGE`) and JSON nesting beyond 32 levels is `MALFORMED_JSON` (400).
