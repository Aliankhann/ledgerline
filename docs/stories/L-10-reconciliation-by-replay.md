# L-10 Reconciliation by full replay

**Status:** QE passed (round 1, pure part; DB/CLI ACs pending), awaiting owner approval  <!-- Draft → Approved → Tests written → In dev → In QE → Done. Only the owner sets Approved. -->
**Day:** 8 · **Branch:** `L-10-reconciliation-by-replay` · **Round:** 0 / 3

## Story

As a reviewing engineer, I want one command that replays the whole ledger into a scratch schema and diffs it against
the live pools, failing with a precise report on any difference, so that "zero drift" is something I can run, not
something I am told.

## Why it matters

The differentiator is a cost-basis number that is replay-verified, not just computed. See
[`docs/problem/evidence.md`](../problem/evidence.md): E-11 (existing tools tell users to "double check results") and
E-19 (Wealthsimple's read path "continuously reconciles against the ledger" and aims to "land on the same number
every time"; it does not say replay or hashes). What reviewers look for is not sourced (A-09).

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
| AC1 | `L10ReconciliationSpec` "L-10 AC1: ..." (pure); exit code `0` and F1 load: NOT WRITTEN (needs DB, CLI, L-09 F1) |
| AC2 | `L10ReconciliationSpec` "L-10 AC2: ..." (pure diff); tampering real tables and exit code `1`: NOT WRITTEN |
| AC3 | `L10ReconciliationSpec` "L-10 AC3: ..." (pure diff); real tables: NOT WRITTEN |
| AC4 | pure part only ("L-10 AC4 (pure part)"); row hashes of `ledger`/`calc`/`recon`: NOT WRITTEN (Integration) |
| AC5 | `L10ReconciliationSpec` "L-10 AC5: ..." and property "Invariant 5 (L-10)"; seed 42 via CLI: NOT WRITTEN |
| AC6 | `L10ReconciliationSpec` "L-10 AC6: ..." (hash format, locale, TZ); two-JVM run: NOT WRITTEN |
| AC7 | `L10ReconciliationSpec` "L-10 AC7: ..." and property "Invariant 6 (L-10)"; three stacks: NOT WRITTEN |
| AC8 | NOT WRITTEN (proposed criterion, owner decision on watermark open) |
| AC9 | NOT WRITTEN (CI job, L-12) |

Property tests: `tests/property/.../L10ReconciliationPropertyTest.kt`.

## Change notes (Dev fills)

- Added `calc/.../Reconciler.kt`: `Reconciler`, `ReconReport`, `PoolDiff`, `DriftKind`, `ReplayHash` per the spec KDoc. It reuses `AcbEngine.replay`, so canonical order applies.
- Pure part only (AC1-AC3, AC4 pure, AC5-AC7 pure). CLI, `recon` schema, exit codes, AC8 and AC9 are not built.
- Hash covers shares and totalAcb only; adjustments are not included. Open: `OWNER-INPUT: C28` in `docs/owner-inputs.md`. The ADR for the format is not written yet.
- L-10 CLI: added `app/.../ReconcileCommand.kt` (replay of every taxpayer's non-reversed activities via `ActivityStore`, diff via `Reconciler`, live pools via `PoolStore`; SELECT-only; exit 0/1/2, never throws) and `main(args)` where `reconcile` runs it with report `build/reconciliation/report.json`; no args serves as before.
- CI compose job runs the command in the app container, copies the report out and uploads it as an artifact; `make reconcile` and `scripts/verify.sh` (two runs must print identical summaries with drift=0) added.
- Not run here: Integration tests (no Docker). AC6-AC8 not built.
