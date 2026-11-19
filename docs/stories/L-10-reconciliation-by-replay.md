# L-10 Reconciliation by full replay

**Status:** Draft  <!-- Draft → Approved → Tests written → In dev → In QE → Done. Only the owner sets Approved. -->
**Day:** 8 · **Branch:** `L-10-reconciliation-by-replay` · **Round:** 0 / 3

## Story

As a reviewing engineer, I want one command that replays the whole ledger into a scratch schema and diffs it against
the live pools, failing with a precise report on any difference, so that "zero drift" is something I can run, not
something I am told.

## Why it matters

The differentiator is a cost-basis number that is replay-verified, not just computed. See
[`docs/problem/evidence.md`](../problem/evidence.md) (E-11 for the "verify it yourself" part; reviewer expectations: see A-09): "Existing ACB calculators give a number with
no way to verify it; reviewers of ledger systems look for reconciliation".

## Notes for QE and Dev

- Invariants covered: **5 Replay determinism** (AC5, AC6) and **6 Order independence of ingestion** (AC7). Drift
  detection covers the whole live/replay comparison.
- **No tax expected values.** The oracle is equality between live and replay, and between replays.
- Design per `docs/architecture.md`: replay into the `recon` schema, then diff against `calc`.
- **Interface proposal (Dev may refine):** `./gradlew :app:run --args="reconcile"` (or `make reconcile`), exit code
  `0` on zero drift and `1` on drift, writing a JSON report to `build/reconciliation/report.json` and a one-line
  summary to stdout, for example `reconcile: pools=15 drift=0 replayHash=<hex> liveHash=<hex>`.
- **Replay hash proposal:** SHA-256 over a canonical serialization of every pool sorted by (taxpayer id, security id),
  integers only, fixed field order, LF line endings. The exact format goes in an ADR.
- Quantities assume the 10⁻⁶ share scale. Fixture F1 is defined in L-09.

## Acceptance criteria

1. **AC1 Zero drift on a clean run.**
   - Input: fresh stack; load F1; wait until the consumer has applied every event; run `reconcile`.
   - Expected: exit code `0`; report `poolsCompared=3`, `drift=[]`; `replayHash` equals `liveHash`.
2. **AC2 A tampered pool is reported precisely.**
   - Input: after AC1, directly in the database, change pool `TP-0001`/`SYN-AAA` shares from `60000000` to `59000000`
     (calc tables are derived and writable by the test). Run `reconcile`.
   - Expected: exit code `1`; `drift` has exactly one item:
     `{"taxpayerId":"TP-0001","securityId":"SYN-AAA","kind":"FIELD_MISMATCH","field":"shares","live":59000000,"replay":60000000}`;
     `replayHash` differs from `liveHash`.
3. **AC3 Missing and extra pools are reported.**
   - Input: after AC1, delete the live `TP-0001`/`SYN-BBB` pool row and insert a live pool `TP-0003`/`SYN-ZZZ` with
     shares `1000000`. Run `reconcile`.
   - Expected: exit code `1`; exactly two drift items: `MISSING_IN_LIVE` for `TP-0001`/`SYN-BBB` and `EXTRA_IN_LIVE`
     for `TP-0003`/`SYN-ZZZ`.
4. **AC4 Reconciliation is read-only on ledger and calc.**
   - Input: SHA-256 of every row in the `ledger` and `calc` schemas before and after the AC1 run.
   - Expected: equal. The `recon` schema is cleared at the start of each run, so two runs in a row give the same
     report.
5. **AC5 Replay twice gives identical hashes.**
   - Input: seed `42` generated year (L-01 AC7) loaded; run `reconcile` twice.
   - Expected: both runs print the same `replayHash`.
6. **AC6 The hash does not depend on the machine.**
   - Input: the same seed `42` ledger replayed once with `TZ=UTC`, JVM locale `en-CA`, and once with
     `TZ=America/Toronto`, locale `fr-CA`.
   - Expected: identical `replayHash`. (The CI job records the hash so a second machine can compare by hand.)
7. **AC7 Arrival order does not change the result (invariant 6).**
   - Input: the seed `42` activities submitted to three fresh stacks in three different orders: file order, reverse
     file order, and a shuffle with seed `7`.
   - Expected: identical `replayHash` and identical `liveHash` on all three stacks.
8. **AC8 Reconciliation while the consumer is running.**
   - Input: start a load of the seed `42` year and run `reconcile` while the consumer is mid-stream.
   - Expected (proposed): the report states the ledger sequence watermark it compared up to, and drift is `[]` for
     that watermark; it does not report pools that are merely behind as drift.
9. **AC9 Drift fails CI.**
   - Input: the CI job that runs after each chaos scenario (L-12).
   - Expected: a non-zero `reconcile` exit fails the job and attaches `report.json` as an artifact.

## Out of scope

- Repairing drift automatically. Reconciliation reports; it never writes to `calc`.
- Reconciling against the broker's book value (L-11 shows that difference; it is not drift).
- Performance of replay (measured in L-13).

## Needs owner decision

- **Canonical serialization for the replay hash** (fields, order, number format). Needs an ADR, since the demo shows
  this hash.
- **Snapshot consistency** (AC8): compare at a ledger sequence watermark (proposed), or require the consumer to be
  idle before reconciling.
- **Tie-breaking order for same-date activities** (see L-03). AC7 fails by design if the tie-breaker is arrival order.
- **Scope of the diff**: pools only, or also pool history and adjustments? Proposal: pools and adjustments.

## Test map (QE fills)

| AC | Test |
| --- | --- |
| AC1 | `tests/spec/.../L10Spec.kt` › "L-10 AC1: …" |

## Change notes (Dev fills)

- …
