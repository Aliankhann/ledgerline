# Ledgerline

An event-sourced double-entry ledger with a streaming Canadian adjusted-cost-base (ACB) engine, built on synthetic data.

<!-- TODO: 10-second GIF of the replay-hash demo (not recorded yet). -->

> **Status:** all 14 stories (L-01 to L-14) are built provisionally and await owner approval. See
> [`docs/status.md`](docs/status.md). Tax values are provisional ([`docs/tax-model.md`](docs/tax-model.md)).

## Quickstart

Requires Docker, JDK 21 and Node 22.

```bash
docker compose up -d --build --wait   # postgres, redpanda, app; runs Flyway migrations
curl localhost:8080/health            # health check
./gradlew build                       # compile, ktlint, spec and property tests
```

The end-to-end demo (replay-hash walkthrough from a fresh clone) is **coming soon**. `./gradlew :tests:test
-PincludeIntegration` runs the Docker-backed tests. `make help` lists the shortcuts.

## What it proves

Only claims backed by an existing test are listed. Tests tagged `Integration` need Docker.

| Claim | Backing test |
| --- | --- |
| Replaying history reproduces the live pools with identical hashes (invariant 5) | [`L10ReconciliationPropertyTest`](tests/property/kotlin/dev/ledgerline/property/L10ReconciliationPropertyTest.kt), [`L10ReconciliationSpec`](tests/spec/kotlin/dev/ledgerline/spec/L10ReconciliationSpec.kt) |
| Duplicate submits change nothing; a changed payload under the same key is rejected | [`L02IngestionSpec`](tests/spec/kotlin/dev/ledgerline/spec/L02IngestionSpec.kt), [`IdempotencyInvariantPropertyTest`](tests/property/kotlin/dev/ledgerline/property/IdempotencyInvariantPropertyTest.kt) |
| A consumer crash mid-stream is recovered with zero drift against a replay (Integration) | [`L12CrashRecoverySpec`](tests/spec/kotlin/dev/ledgerline/spec/L12CrashRecoverySpec.kt), [`L12InboxPropertyTest`](tests/property/kotlin/dev/ledgerline/property/L12InboxPropertyTest.kt) |
| A back-dated correction gives the same pool as a clean replay | [`L09RecomputeSpec`](tests/spec/kotlin/dev/ledgerline/spec/L09RecomputeSpec.kt), [`L09RecomputePropertyTest`](tests/property/kotlin/dev/ledgerline/property/L09RecomputePropertyTest.kt) |
| Applying an activity then its reversal restores prior balances | [`ReversalSymmetryPropertyTest`](tests/property/kotlin/dev/ledgerline/property/ReversalSymmetryPropertyTest.kt), [`L04ReversalSpec`](tests/spec/kotlin/dev/ledgerline/spec/L04ReversalSpec.kt) |

Not yet proven: throughput under a burst (harness exists, nothing measured), and Toxiproxy network-fault scenarios.

## Modules

| Module | Role |
| --- | --- |
| `core/` | Domain-neutral types: integer money, ids, plugin contract. No I/O. |
| `plugins/` | Domain plugins (a cash-balance plugin, L-14). |
| `ledger/` | Posting, idempotency, reversals, outbox; owns the Flyway migrations. |
| `calc/` | ACB pools, superficial loss, FX, inbox consumer, reconciler, book-value comparison. |
| `ingest/` | CSV parser and seeded synthetic generator. |
| `app/` | Ktor service that wires the modules. |
| `bench/` | Burst load generator and report scripts. |
| `tests/` | QE-owned spec, property and chaos tests. |
| `ui/` | React explorer. |

Data flow and schemas: [`docs/architecture.md`](docs/architecture.md).

## Benchmark results

Not yet measured; see [`docs/benchmarks.md`](docs/benchmarks.md).

## Decisions

ADRs live in [`docs/adr/`](docs/adr/): idempotency key plus payload hash (0001), integer money and fixed-point shares
(0002), corrections by reversal (0003), pluggable rule set (0004), chart of accounts and append-only store (0005),
calc reads ledger activity on reversal (0006). Drafts are pending the owner's rewrite.

## Limits and disclaimers

- Synthetic data only; no broker APIs, scraping or real credentials.
- Simplified tax model with provisional values. This is not tax or financial advice.
- No affiliation with any company or product mentioned.

## Credits

Design inspired by public engineering writing on ledgers and event sourcing, including
[Wealthsimple engineering](https://engineering.wealthsimple.com). Worked-example sources are cited in
[`docs/tax-model.md`](docs/tax-model.md).
