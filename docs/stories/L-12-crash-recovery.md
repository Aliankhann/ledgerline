# L-12 Crash recovery

**Status:** Draft  <!-- Draft → Approved → Tests written → In dev → In QE → Done. Only the owner sets Approved. -->
**Day:** 5 (part 1), 9 (part 2) · **Branch:** `L-12-crash-recovery` · **Round:** 0 / 3

## Story

As a reviewing engineer, I want the consumer to apply every event exactly once, even when it is killed mid-batch,
the database connection is flaky, a partition is redelivered or a rebalance happens, so that the pools always end up
equal to a full replay.

## Why it matters

Crash-safe, exactly-once effects are what separates a ledger demo from a toy, and they are central to the demo's
"kill it, restart, zero drift" moment. See [`docs/problem/evidence.md`](../problem/evidence.md): E-17 (Wealthsimple
makes a repeated event "a no-op") and E-19 (streaming read path that recomputes to "the same number every time").
Crash and redelivery handling itself is not sourced (assumption A-12).

## Notes for QE and Dev

- Design per `docs/build-plan.md`: at-least-once delivery plus an idempotent consumer; the inbox row (event id), the
  state change and the Kafka offset commit in **one Postgres transaction**.
- **This story spans two days.** Part 1 (day 5, AC1–AC4) builds the consumer and the kill-and-restart test, alongside
  L-06. Part 2 (day 9, AC5–AC9) is the chaos suite. See the split proposal below.
- **Oracle before L-10 exists.** On day 5 the reconciliation command (L-10, day 8) is not built yet. Part 1 compares
  live pools against a from-scratch rebuild of the same events in a fresh state (a QE test-support helper). From day 8
  on, the same tests also run `reconcile` and require exit code `0`.
- **No tax expected values.** Every oracle is equality with a replay.
- Each chaos scenario is a script under `chaos/` and a test under `tests/chaos/` tagged `Integration`. Names and
  timings are proposals the Dev and QE may refine.
- Workload W42: the seed `42` generated year from L-01 AC7 (5 taxpayers), posted through the funnel.

## Acceptance criteria

**Part 1, day 5**

1. **AC1 Each event is applied once and recorded in the inbox.**
   - Input: post A1 (L-02) and let the consumer apply it.
   - Expected: exactly one inbox row with A1's `eventId`; the committed consumer offset for that partition is past
     A1's message; the inbox row, the pool change and the offset are in the same transaction (QE checks by forcing a
     rollback after the state write: none of the three is visible afterwards).
2. **AC2 A redelivered event changes nothing.**
   - Input: after AC1, publish A1's message again with the same `eventId` (byte-identical, as L-05 AC5 can produce).
   - Expected: no pool change; inbox still has one row for that `eventId`; offset advances past the duplicate.
3. **AC3 Kill mid-batch, restart, zero drift.**
   - Input: post W42; when the consumer has committed about half of the events, `kill -9` it (or `docker kill`); restart
     it and wait until its lag is `0`.
   - Expected: every `eventId` in W42 appears exactly once in the inbox; live pools equal the from-scratch rebuild
     (and, from day 8, `reconcile` exits `0`).
4. **AC4 Kill at several points.**
   - Input: repeat AC3 killing after the 1st event, after about half, and one event before the end (3 runs, fresh
     stack each time).
   - Expected: zero drift in all three runs.

**Part 2, day 9**

5. **AC5 Flaky database connection (Toxiproxy).**
   - Input: route the consumer's Postgres connection through Toxiproxy; while W42 is being consumed, add 500 ms latency
     for 30 seconds, then reset connections every 2 seconds for 30 seconds; remove the toxics.
   - Expected: after lag reaches `0`, `reconcile` exits `0`; no `eventId` appears twice in the inbox.
6. **AC6 Whole-partition redelivery changes nothing.**
   - Input: after W42 is fully applied and `reconcile` is clean, reset the consumer group offsets to earliest for all
     partitions of `ledgerline.activities.v1` and restart the consumer.
   - Expected: after lag reaches `0`, the live hash is unchanged from before the reset; inbox row count unchanged;
     `reconcile` exits `0`.
7. **AC7 Rebalance does not double-apply.**
   - Input: while W42 is being consumed by one consumer, start a second instance in the same group for 30 seconds,
     then stop it.
   - Expected: after lag reaches `0`, no `eventId` appears twice in the inbox and `reconcile` exits `0`.
8. **AC8 Late correction during consumption.**
   - Input: while W42 is being consumed, post the L-09 AC1 style correction of one W42 buy dated 6 months earlier.
   - Expected: after lag reaches `0`, `reconcile` exits `0`.
9. **AC9 Each scenario runs from one command and reconciles.**
   - Input: `./gradlew :tests:chaosTest -PincludeIntegration` (from `CLAUDE.md`).
   - Expected: runs AC3 to AC8; each scenario ends by running `reconcile` and fails if drift is not zero; the output
     lists each scenario name with pass or fail and the number of events consumed.

## Out of scope

- Producer-side crash safety (L-05).
- Multi-region or multi-cluster failure.
- Performance under chaos (L-13 measures the burst; this story only checks correctness).
- Automatic drift repair.

## Needs owner decision

- **Proposed split (needs approval, would exceed 14 stories).** This story is two days of work. Proposal: L-12a
  "Exactly-once consumer and kill-and-restart" (day 5, AC1–AC4) and L-12b "Chaos suite" (day 9, AC5–AC9). If the owner
  keeps one story, it stays `In dev` from day 5 to day 9.
- **Where the consumer lives**: inside the `app` process or as a separate service in `docker-compose.yml`. Killing it
  alone (AC3) is easier if separate.
- **Batch size and commit frequency**: one transaction per event (simplest to prove) or per batch (faster). AC3's
  "about half" kill point assumes per-event or small batches.
- **Toxiproxy values** in AC5 (500 ms, 2-second resets, 30 seconds) are BA placeholders.

## Test map (QE fills)

| AC | Test |
| --- | --- |
| AC1 | `tests/spec/.../L12Spec.kt` › "L-12 AC1: …" |

## Change notes (Dev fills)

- …
