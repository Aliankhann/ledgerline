# L-05 Reliable event publication

**Status:** In QE  <!-- Draft → Approved → Tests written → In dev → In QE → Done. Only the owner sets Approved. -->
**Day:** 4 · **Branch:** `L-05-reliable-event-publication` · **Round:** 1 / 3

## Story

As a reviewing engineer, I want every posted ledger entry to reach the event stream even if the service crashes
between the database write and the publish, so that the calculation side never misses or double-counts an activity.

## Why it matters

Writing to a database and then to Kafka separately loses or duplicates events on a crash; the transactional outbox is
the standard fix. See [`docs/problem/evidence.md`](../problem/evidence.md): no sourced evidence yet (assumption
A-12). E-19 shows only that Wealthsimple separates a write path from a streaming read path; it does not mention an
outbox or Kafka.

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
- **Should the L-03 direct `post`/`append` APIs publish?** (QE, phase B round 1.) Entries written through
  `LedgerRepository.post`/`append` get no outbox row, and their reversals publish nothing (placeholder C25, ADR 0005).
  No L-05 AC or invariant covers them: every AC posts through the service, and the service never calls these APIs. QE
  wrote no test and filed no bug. The story statement says "every posted ledger entry"; confirm that excluding these
  entries (which carry no taxpayer) is intended, or require `post` to publish too.

## Test map (QE fills)

Written provisionally while the story is Draft (owner instruction). API contract (`OutboxRelay`, `OutboxStore`,
`OutboxProducers`, `OutboxTopic`, `OutboxEvent`, `DrainResult` in `dev.ledgerline.ledger.outbox`) and the message
shape are in the KDoc of `L05OutboxSpec`. Placeholders: C5 (append-only `ledger.outbox_delivery`), C25 (reversals and
corrections write outbox events), C26 (at-least-once, key = taxpayer id, event id = outbox row id). Crashes are
injected at the producer given to the relay, so production code needs no fault hook; AC4, AC5 and AC9 halt a real
child JVM with `Runtime.halt`. AC6 "stop Redpanda" is `docker pause` (keeps the advertised port). Every test is
Integration (Postgres 16 + `redpandadata/redpanda:v24.2.7`).
`S` = `tests/spec/kotlin/dev/ledgerline/spec/L05OutboxSpec.kt`;
`P` = `tests/property/kotlin/dev/ledgerline/property/OutboxDeliveryPropertyTest.kt`.

| AC | Test |
| --- | --- |
| AC1 | S "L-05 AC1: posting A1 commits exactly one entry and one outbox row referencing it, in one transaction" (same `xmin`) |
| AC2 | S "L-05 AC2: a failure after the ledger insert and before commit writes neither; 5xx; the retry is 201" (deferred test trigger fails the commit) |
| AC3 | S "L-05 AC3: within 10 s the default topic has one message: key TP-0001, header eventId, stable value shape" |
| AC4 | S "L-05 AC4: a process halted after commit and before send loses nothing; after restart exactly one message" (two variants: halt right after the commit; halt inside the relay's first send). "201 or no response" is checked as the client's retry being a duplicate |
| AC5 | S "L-05 AC5: a process halted after the broker ack and before marking leaves 1 or 2 identical copies; then marks" |
| AC6 | S "L-05 AC6: with the broker down, 10 postings return 201; all 10 drain within 60 s of its return" |
| AC7 | S "L-05 AC7: 50 postings for TP-0001 appear with key TP-0001 in strictly increasing ledger sequence order"; S "L-05 ordering: per taxpayer key, partition order follows outbox order (5 taxpayers posting concurrently)" |
| AC8 | S "L-05 AC8: a reversal is published as ENTRY_REVERSED after ACTIVITY_POSTED, referencing the original entryId" |
| AC9 | S "L-05 AC9: 200 activities across 5 taxpayers, drainer halted 5 times at random fault points: no loss" (child JVM halts, seed 20251120); P "C26 / L-05 AC9 (property): with injected crashes every outbox row is delivered and ids equal the outbox" (12 generated plans, in-process aborts, reversals mixed in) |
| C5 | S "L-05 C5: delivery is appended to ledger.outbox_delivery; no other ledger row changes and it is append-only" |
| C25 | S "L-05 C25: a correction publishes ENTRY_REVERSED and ACTIVITY_POSTED; repeats and refusals publish nothing" |
| Contract | S "L-05 contract: drainOnce(max) sends at most max rows in outbox order and never re-sends delivered rows"; S "L-05 contract: start() drains in the background; after stop() new rows wait for the next relay" |

Phase B, round 1 (QE attack tests, all Integration). `D` = `tests/spec/kotlin/dev/ledgerline/spec/L05AttackDeliverySpec.kt`;
`R` = `tests/spec/kotlin/dev/ledgerline/spec/L05AttackRobustnessSpec.kt`.

| Covers | Test | Bug |
| --- | --- | --- |
| AC7 | D "L-05 attack AC7 (B-015): a failed ack mid-drain must not let a later event of that taxpayer be seen first" | B-015 (major) |
| AC7, C5, C26 | D "L-05 attack: two relays draining one outbox concurrently record each event once; copies share bytes and order" | — |
| AC6 (scale) | D "L-05 attack: 10 000 pending events drain within a generous bound (timing printed)" (bound 120 s) | — |
| Story, AC3 | R "L-05 attack (B-016): an activity accepted with a 1.5 MB description is published or refused, never stuck" | B-016 (major) |
| AC7, C26 | R "L-05 attack (B-017): one undecodable outbox row does not stop other taxpayers or re-send earlier rows" | B-017 (minor) |
| C5 | R "L-05 attack C5: the app role cannot update, delete or bypass ledger.outbox_delivery; owner upserts fail" | — |

## Change notes (Dev fills)

- 2026-11-20 (dev, round 0, provisional while Draft). API of the `L05OutboxSpec` KDoc in
  `dev.ledgerline.ledger.outbox`: `OutboxTopic`, `OutboxEventType`, `OutboxEvent`, `DrainResult`, `OutboxStore`
  (`events()`, `pendingEventIds()` as an anti-join of outbox minus delivery), `OutboxProducers` (idempotent,
  `acks=all`, 5 in flight), `OutboxRelay` (`drainOnce`, `start`, `stop`, `close`). Migration V7: event columns on
  `ledger.outbox` (type, taxpayer, stored JSON body), a sealing trigger (an outbox row only in its entry's
  transaction), and the append-only `ledger.outbox_delivery` (C5) with SELECT/INSERT for the app role. Reversals and
  corrections write their outbox rows in the same transaction (C25). Delivery rows are written after the broker's ack,
  one transaction per drain (C26). `Main` starts the relay in the service process. Details and trade-offs (per-key
  order on a mid-drain broker failure, pre-V7 rows, L-03 `post`/`append` entries not published) in draft ADR 0005.
- Ran `./gradlew ktlintFormat build --rerun-tasks`: BUILD SUCCESSFUL; 187 tests, 67 passed, 120 skipped (Integration),
  0 failed. All 15 L-05 tests are Integration and did not run here (no Docker). Not a substitute: a throwaway smoke
  program (not committed) against a local Postgres 16 with Kafka's `MockProducer` checked the migrations, the event rows
  for post/reverse/correct, drain batching and resume, held-back rows after a failed ack, byte-identical re-sends, the
  append-only and sealing refusals, and a pre-V7 outbox row draining after V7. Redpanda behaviour is unverified until CI.
- 2026-11-20 (dev, round 2: B-015, B-016, B-017). `OutboxRelay` sends strictly serially per taxpayer and in parallel
  across taxpayers; a failed send stops that taxpayer for the drain and the next drain resends from it (B-015).
  OWNER-INPUT: C27 (placeholder): ingestion refuses an event whose message value would exceed
  `OUTBOX_MAX_EVENT_BYTES` (default 900 KiB) with `EVENT_TOO_LARGE` (422; per-line CSV rejection), nothing written
  (B-016). Migration V8 adds the append-only `ledger.outbox_parked` (SELECT/INSERT for the app role): the relay parks an
  event it can never publish (undecodable body, over the max, or `RecordTooLargeException`) with a reason, logs it,
  never retries it, and holds back only that taxpayer's later events (B-016, B-017). `DrainResult` gains
  `parked` (default 0); `OutboxStore.parkedEvents()` lists parked rows; parked rows stay in `pendingEventIds()`.
  Draft ADR 0005 and `docs/owner-inputs.md` (C27) updated.
- Ran `./gradlew --init-script <scratchpad>/mirror.init.gradle.kts ktlintFormat build --rerun-tasks`: BUILD SUCCESSFUL;
  193 tests, 67 passed, 126 skipped (Integration), 0 failed. All L-05 tests, including the six new attack tests, are
  Integration and did not run here (no Docker); CI on `l-05` is the authority.
