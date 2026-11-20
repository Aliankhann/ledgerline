# Benchmarks

_Record hardware, versions and seed for every run. Report actuals, even when they miss the target._

| Measurement | Target (set before running) | Actual | Hardware | Seed | Commit |
| --- | --- | --- | --- | --- | --- |

Note: absolute ingest throughput is measured in L-13, not in CI. L-02 tests only assert relative bounds (re-upload <= 2x first import, < 60 ms/row) because runner timings vary about 2x.
