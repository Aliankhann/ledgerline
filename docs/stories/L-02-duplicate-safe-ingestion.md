# L-02 Duplicate-safe ingestion

**Status:** Draft  <!-- Draft → Approved → Tests written → In dev → In QE → Done. Only the owner sets Approved. -->
**Day:** 4 · **Branch:** `L-02-duplicate-safe-ingestion` · **Round:** 0 / 3

## Story

As a self-directed investor, I want re-uploading the same activity or the same CSV to change nothing, and a changed
activity under an old key to be refused with a clear error, so that retries and double uploads can never corrupt my
cost basis.

## Why it matters

Duplicate-safe submission is the core trust property of payment and brokerage ledgers, and duplicate uploads are a
common way to corrupt a spreadsheet ACB. See [`docs/problem/evidence.md`](../problem/evidence.md) (evidence ID TBD):
"Idempotency keys with payload checks are standard practice for financial APIs; duplicate imports corrupt
cost-basis records".

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

| AC | Test |
| --- | --- |
| AC1 | `tests/spec/.../L02Spec.kt` › "L-02 AC1: …" |

## Change notes (Dev fills)

- …
