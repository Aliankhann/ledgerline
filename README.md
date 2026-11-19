# Ledgerline

An event-sourced double-entry ledger with a streaming Canadian adjusted-cost-base (ACB) engine, where every number is
traceable, replay-verified and safe under failure.

> **Status: scaffolding (step 0.1).** Nothing below "Quickstart" is built yet. See [`docs/status.md`](docs/status.md).

## Quickstart

Requires Docker, JDK 21 and Node 22.

```bash
docker compose up -d --build --wait   # postgres, redpanda, app; runs Flyway migrations
curl localhost:8080/health            # {"status":"ok",...}
./gradlew build                       # compile, ktlint, spec and property tests
```

`make help` lists every shortcut. The UI dev server is `make ui-dev` (http://localhost:5173).

## What it will prove

| Claim | Backed by |
| --- | --- |
| Replaying a year twice gives byte-identical hashes | _tests/property, from step 5_ |
| Duplicate submits are ignored; changed payloads under the same key are rejected | _tests/spec L-02_ |
| A consumer crash mid-stream causes zero drift | _tests/chaos L-12_ |
| A late correction recomputes only what it affects | _tests/spec L-09_ |
| Measured throughput under a market-open burst | _docs/benchmarks.md, L-13_ |

## Architecture

Activities enter once through an idempotent funnel, land in an append-only double-entry ledger, go out through a
transactional outbox to Redpanda, and are folded into ACB pools by a consumer with a transactional inbox.
Reconciliation replays the whole ledger and diffs it against the live pools. Details are in
[`docs/architecture.md`](docs/architecture.md).

| Module | Responsibility |
| --- | --- |
| `core/` | Integer money, ids, `DomainPlugin` contract (no I/O) |
| `ledger/` | Posting, idempotency, reversals, outbox, Flyway migrations |
| `calc/` | ACB pools, superficial loss, FX, consumer, reconciliation |
| `ingest/` | CSV parser, seeded synthetic generator |
| `app/` | Ktor service |
| `tests/` | All tests: `spec/`, `property/`, `chaos/` (QE-owned) |
| `ui/` | React + TypeScript explorer |

Stack: Kotlin 2.0 / JVM 21, Ktor 3, PostgreSQL 16, Flyway, Redpanda, Kotest, Testcontainers, Toxiproxy, React, Vite.

## How it's built

A BA agent writes stories, a QE agent writes tests from each story before any code exists, and a Dev agent makes them
pass. The owner approves every story, decision and merge. CI enforces the role boundaries: a Dev commit can't touch
`tests/`. See [`docs/agent-workflow.md`](docs/agent-workflow.md) and [`CLAUDE.md`](CLAUDE.md).

## Decisions

Decision records are in [`docs/adr/`](docs/adr/), each with the alternatives that were rejected.

## Limits and disclaimers

Synthetic data only. Simplified tax model ([`docs/tax-model.md`](docs/tax-model.md)). Not tax or financial advice.
Independent project with no affiliation with, or endorsement by, any company mentioned.

## Credits

The architecture pattern is inspired by public engineering writing, notably
[Wealthsimple Engineering: Inside the Book of Record](https://engineering.wealthsimple.com/inside-the-book-of-record).
