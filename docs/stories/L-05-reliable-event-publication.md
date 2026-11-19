# L-05 Reliable event publication

**Status:** Draft  <!-- Draft → Approved → Tests written → In dev → In QE → Done. Only the owner sets Approved. -->
**Day:** 4 · **Branch:** `L-05-reliable-event-publication` · **Round:** 0 / 3

## Story

As a reviewing engineer, I want every posted ledger entry to reach the event stream even if the service crashes
between the database write and the publish, so that the calculation side never misses or double-counts an activity.

## Why it matters

Writing to a database and then to Kafka separately loses or duplicates events on a crash; the transactional outbox is
the standard fix. See [`docs/problem/evidence.md`](../problem/evidence.md) (evidence ID TBD): "Dual writes to a
database and a message broker are unsafe under crashes; the outbox pattern is the published remedy".

## Notes for QE and Dev

- Design per `docs/build-plan.md`: outbox row written in the same Postgres transaction as the ledger entry, drained to
  topic `ledgerline.activities.v1`, message key = taxpayer id (see `docs/architecture.md`).
- **What "loses or duplicates nothing" means here.** The outbox gives at-least-once delivery. A crash after the
  broker accepts a message but before the outbox row is marked sent can put the same event on the topic twice. This
  story guarantees: no event is lost; every copy of an event carries the same `eventId` and identical bytes; no two
  distinct events share an `eventId`. Applying each `eventId` once is the consumer inbox's job (L-12). The owner should
  confirm this wording (see below).
- **Crash injection proposal.** A test-only fault point, enabled by configuration and off by default, that halts the
  process (`Runtime.halt`, equivalent to `kill -9`) at a named step: `after-commit-before-send` and
  `after-send-before-mark`. The Dev may propose another mechanism; QE needs a deterministic way to crash at each step.
- Tests that need Docker are tagged `Integration` (see `CLAUDE.md`). Names are proposals the Dev may refine.
- Activity A1 is defined in L-02.

## Acceptance criteria

1. **AC1 Ledger entry and outbox row commit together.**
   - Input: post A1 (`TP-0001-2025-0002`).
   - Expected: in the same transaction, exactly 1 ledger entry and exactly 1 outbox row referencing that entry.
2. **AC2 A failed posting writes neither.**
   - Input: post A1 with the database made to fail after the ledger insert and before commit (for example a test
     trigger or the fault point `before-commit`).
   - Expected: 0 ledger entries and 0 outbox rows for `TP-0001-2025-0002`; the API returns a `5xx`; a later retry of A1
     posts normally (`201`).
3. **AC3 The published message has a stable shape.**
   - Input: post A1 and let the drainer run.
   - Expected: within 10 seconds, topic `ledgerline.activities.v1` has one message with key `TP-0001`, a header
     `eventId` equal to the outbox row's event id, and a value that includes `eventId`, `eventType=ACTIVITY_POSTED`,
     `idempotencyKey=TP-0001-2025-0002`, `entryId` and the ledger sequence.
4. **AC4 Crash after commit, before send: nothing is lost.**
   - Input: fault point `after-commit-before-send` enabled; post A1; the process halts; restart it with the fault off.
   - Expected: the API call either returned `201` or failed with no response; in both cases, after restart the topic
     has exactly 1 message with A1's `eventId` within 30 seconds, and the ledger has exactly 1 entry for A1.
5. **AC5 Crash after send, before marking: one event id, identical copies.**
   - Input: fault point `after-send-before-mark` enabled; post A1; the process halts after the broker acknowledges;
     restart with the fault off.
   - Expected: the topic has 1 or 2 messages with A1's `eventId`, and if 2, their keys, headers and values are
     byte-identical. No message has any other `eventId` for A1. The outbox row is marked sent after restart.
6. **AC6 Broker outage: postings still succeed and drain later.**
   - Input: stop Redpanda; post 10 activities `TP-0001-2025-0101` to `TP-0001-2025-0110` (deposits of `1000` CAD each,
     dated 2025-04-01); start Redpanda.
   - Expected: all 10 posts return `201` while the broker is down; within 60 seconds of the broker returning, the topic
     has a message for each of the 10 event ids.
7. **AC7 Per-taxpayer order is preserved.**
   - Input: post 50 activities for `TP-0001` in a row (deposits `TP-0001-2025-0201` to `-0250`, 100 CAD each).
   - Expected: on the topic, all 50 have key `TP-0001` and appear in strictly increasing ledger sequence order.
8. **AC8 Reversals are published too.**
   - Input: post A1, then reverse it (L-04 AC1).
   - Expected: two messages for `TP-0001`: `ACTIVITY_POSTED` then `ENTRY_REVERSED`, the second referencing the first's
     `entryId`.
9. **AC9 Repeated crash and restart, no loss (integration property).**
   - Input: post 200 generated activities across 5 taxpayers while halting the drainer at a random fault point 5 times
     and restarting it each time.
   - Expected: the set of distinct `eventId`s on the topic equals the set of outbox rows (200); no `eventId` appears
     with differing content.

## Out of scope

- The consumer, inbox and applying events exactly once (L-12).
- Kafka transactions or exactly-once producer semantics (rejected in `docs/build-plan.md`).
- Outbox retention and clean-up.
- Topic partition count and retention settings beyond what the tests need.

## Needs owner decision

- **Wording of "loses or duplicates nothing"** (above). Confirm that duplicate copies of one `eventId` on the topic
  are acceptable when the consumer deduplicates, or require something stronger.
- **How the outbox records "sent"**. An `UPDATE` of a `sent_at` column on the outbox row, or an append-only
  "published" table or cursor? The outbox is in the `ledger` schema; if invariant 3 applies to it too, an `UPDATE` is
  not allowed. Needs an ADR.
- **Fault-injection mechanism** in production code (config flag) versus external only (`docker kill` timed by
  Toxiproxy). A config flag is deterministic but adds test hooks to production code.
- **Drainer placement**: in the same process as the API (simplest) or a separate process.
- **Latency bounds in AC3, AC4 and AC6** (10, 30 and 60 seconds) are BA placeholders, not measured.

## Test map (QE fills)

| AC | Test |
| --- | --- |
| AC1 | `tests/spec/.../L05Spec.kt` › "L-05 AC1: …" |

## Change notes (Dev fills)

- …
