# L-13 Burst benchmark and report

**Status:** Draft  <!-- Draft → Approved → Tests written → In dev → In QE → Done. Only the owner sets Approved. -->
**Day:** 9 · **Branch:** `L-13-burst-benchmark` · **Round:** 0 / 3

## Story

As a reviewing engineer, I want one command that runs a reproducible load, including a market-open burst, and writes
a results table with the hardware, versions and seed, so that I can rerun it and check every published number.

## Why it matters

Correct positions under bursty load are the hard part of a retail brokerage, and published numbers are only credible
if they can be reproduced. See [`docs/problem/evidence.md`](../problem/evidence.md): E-19 ("tens of thousands of
trades, transfers, and deposits land in the same minute" at market open, per Wealthsimple) and E-20 (weak: its ledger
team built a load-testing framework for a client-balance service). That reviewers expect reproducible benchmarks is
not sourced (A-08, A-09).

## Notes for QE and Dev

- **No numbers in this story are targets or results.** Targets are set by the owner before running (build plan:
  "Set yourself targets before you run them"); actuals come only from a real run. Nothing here may be filled in by an
  agent.
- **Interface proposal (Dev may refine):** `make bench SEED=42` (wrapping a script in `bench/`) against the running
  compose stack; `make bench-smoke` for a small CI-sized run. Targets live in `bench/targets.yml`, committed before the
  run. Output goes to `bench/reports/<UTC timestamp>-seed<seed>/` with `report.md`, `report.json` and raw data files.
- Workload is generated with the L-01 generator, so the same seed gives the same input.

## Acceptance criteria

1. **AC1 One command produces a report.**
   - Input: running stack (`docker compose up -d --build --wait`), committed `bench/targets.yml`, `make bench SEED=42`.
   - Expected: exit code `0`; a new directory `bench/reports/<timestamp>-seed42/` containing `report.md`,
     `report.json`, `latencies.csv` (one row per request) and `lag.csv` (one row per sample).
2. **AC2 The report records the environment.**
   - Input: the AC1 `report.json`.
   - Expected: non-empty fields `cpuModel`, `logicalCores`, `memoryGiB`, `os`, `dockerVersion`, `jvmVersion`,
     `postgresVersion`, `redpandaVersion`, `gitCommit`, `gitDirty` (boolean), `seed=42`, and workload parameters
     (`taxpayers`, `activities`, `burstSize`, `burstWindowSeconds`).
3. **AC3 The report has every measurement, with target and actual side by side.**
   - Input: the AC1 `report.md`.
   - Expected: one table row each for: ingest throughput (activities per second); write latency p50, p95, p99 (ms);
     burst peak consumer lag (messages); burst time to drain (s); full replay time for the year (s); recompute time
     after a late correction reaching 1, 6 and 12 months back (s). Each row has `Target`, `Actual` and `Met` (`yes` or
     `no`) columns.
4. **AC4 Targets must exist before the run.**
   - Input: `make bench SEED=42` with `bench/targets.yml` missing, or with a measurement from AC3 missing in it.
   - Expected: exit code non-zero before any load is sent, with a message naming the missing target.
5. **AC5 Missed targets are reported, not hidden.**
   - Input: `bench/targets.yml` with an ingest throughput target the run cannot meet (for example a test-only value of
     `1000000000` activities per second).
   - Expected: the run completes, the row shows `Met = no`, and the command still exits `0` (a miss is a result, not a
     failure).
6. **AC6 Same seed, same workload.**
   - Input: `make bench-smoke SEED=42` twice.
   - Expected: both reports show the same `workloadSha256` (hash of the generated input), even though timings differ.
7. **AC7 Numbers from a broken run are not published.**
   - Input: a run in which any activity is rejected by the funnel, or `reconcile` (L-10) after the run reports drift.
   - Expected: exit code non-zero; the report is written with status `INVALID` and the reason at the top; no row is
     appended to `docs/benchmarks.md`.
8. **AC8 Comparison with the last stored benchmark.**
   - Input: `make bench SEED=42 COMPARE=1` when `docs/benchmarks.md` has a previous row for the same seed and workload.
   - Expected: the report adds a `% change vs last` column per measurement, and the command exits non-zero if any
     measurement is worse than the performance threshold in `CLAUDE.md` (currently PROPOSED, owner to confirm).
9. **AC9 CI smoke run.**
   - Input: `make bench-smoke` in GitHub Actions.
   - Expected: completes and produces a valid `report.json` (all AC2 fields present); the result is uploaded as an
     artifact. CI does not compare smoke timings against targets.

## Out of scope

- Choosing targets (owner) and reporting actual results in posts (owner, after a real run).
- Incremental update versus recompute-from-date comparison (cut-list item 5; could be added as one extra row if the
  owner wants it).
- Grafana dashboards (cut-list item 1).
- Multi-machine or cloud load generation.

## Needs owner decision

- **Targets for every AC3 measurement**, set before the first run.
- **Performance gate threshold** (`CLAUDE.md` says PROPOSED ≤10% slower). AC8 depends on it.
- **Burst definition**: number of taxpayers, number of activities and the window (the build plan says "a large batch
  of trades across many taxpayers injected in under a minute").
- **Who writes `docs/benchmarks.md`**: the command appends a row (proposed), or the owner copies results by hand.
- **Load tool**: k6 or a Kotlin load generator (build plan leaves it open).

## Test map (QE fills)

| AC | Test |
| --- | --- |
| AC1 | `tests/spec/.../L13Spec.kt` › "L-13 AC1: …" |

## Change notes (Dev fills)

- …
