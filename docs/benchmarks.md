# Benchmarks

_Record hardware, versions and seed for every run. Report actuals, even when they miss the target._

| Measurement | Target (set before running) | Actual | Hardware | Seed | Commit |
| --- | --- | --- | --- | --- | --- |

Note: absolute ingest throughput is measured in L-13, not in CI. L-02 tests only assert relative bounds (re-upload <= 2x first import, < 60 ms/row) because runner timings vary about 2x.

Change (batched CSV import): `importCsv` and `confirm` submit rows in chunks of 500. Each chunk is one transaction with one append-lock acquisition, one key lookup, and JDBC batch inserts for entry, lines, activity and outbox rows. Per-row outcomes (NEW, DUPLICATE, CONFLICT, REJECTED, in-file repeats) are unchanged. A chunk that fails in the database or has an oversized event is rolled back and redone row by row. Single-activity POST is unchanged. Re-measure with the same L-13 run before comparing.
