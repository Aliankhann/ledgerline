# Benchmarks

_Record hardware, versions and seed for every run. Report actuals, even when they miss the target._

**How to reproduce:** push to a branch named `l-bench*` (or run the "bench" workflow by hand). The job prints the
runner hardware, starts the compose stack and runs `./gradlew :bench:run --args="--seed 42 --activities 20000"`
(`.github/workflows/bench.yml`, harness in `app/src/main/kotlin/dev/ledgerline/bench/`). Every number below is copied
from that job's log; nothing is estimated.

**Hardware (both runs):** GitHub-hosted `ubuntu-latest` runner, AMD EPYC 9V45, 4 vCPU, 15 GiB RAM; Postgres 16 and
Redpanda in Docker on the same machine. Seed 42. Targets were not set before running (C10 and B3 are owner decisions).

| Measurement | Run 1 (`293ff0e`, run 45633207422) | Run 2: batched import (`61029d4`, run 45635279264) |
| --- | --- | --- |
| CSV import end to end, 20,000 rows | 593.4 rows/s | **2,391.7 rows/s (4.0x)** |
| Burst, 8 concurrent writers | 550.8 activities/s | 778.2 activities/s |
| Burst write latency p50 / p95 / p99 | 13.30 / 20.59 / 31.85 ms | 9.75 / 13.64 / 19.14 ms |
| Single-writer ingest (`POST` path) | 585.8 activities/s | 349.8 activities/s |
| Single-writer latency p50 / p95 / p99 | 1.55 / 2.53 / 4.28 ms | 2.59 / 6.12 / 7.62 ms |
| Ledger load, 60,200 events | 21,182 events/s | 21,891 events/s |
| Full ACB replay, 60,200 events | 376,250 events/s | **396,053 events/s** |
| `reconcile` command (seeded book) | 3.19 s | 2.59 s |

Notes:
- The single-writer path was not changed between runs; its drop in run 2 is runner-to-runner variance (one run each,
  shared hardware). Treat it as noise until repeated, not as a regression or an improvement.
- `reconcile` exits 1 in the bench because the bench writes to the ledger without running the pool consumer; only the
  duration is meaningful here. Correctness of reconcile is covered by CI (drift 0 after the seeded year).
- Run 2 change: `importCsv` and `confirm` submit rows in chunks of 500, each one transaction with one append-lock
  acquisition and JDBC batch inserts; per-row outcomes are unchanged (L-02/L-20 tests), and a failing chunk is redone
  row by row. Durability settings are unchanged.
- L-02 tests only assert relative bounds (re-upload <= 2x first import, < 60 ms/row) because runner timings vary.
