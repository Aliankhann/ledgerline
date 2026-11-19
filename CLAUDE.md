# Ledgerline: shared rules for every agent

Ledgerline is an event-sourced double-entry ledger with a streaming Canadian ACB (adjusted cost base) engine,
built as a portfolio project on synthetic data. The plan is in `docs/build-plan.md`; the agent process is in
`docs/agent-workflow.md`. This file holds the facts that the BA, Dev and QE agents all read. When this file and an
agent file disagree, this file wins; when either disagrees with the owner, the owner wins.

## Roles (summary; full rules in `docs/agent-workflow.md`)

| Role | Writes | Never touches |
| --- | --- | --- |
| `ba` (`.claude/agents/ba.md`) | `docs/problem/`, `docs/stories/`, launch drafts | code, tests |
| `qe` (`.claude/agents/qe.md`) | `tests/`, `docs/bugs/` | production code (`core/ ledger/ calc/ ingest/ app/ ui/`) |
| `dev` (`.claude/agents/dev.md`) | production code, build files, draft ADRs, change notes in story files | `tests/` |
| owner (the human) | everything; approves stories, ADRs and merges | — |

- One story at a time. A story is not done until QE signs off **and** every gate below passes **and** the owner approves.
- At most three QE↔Dev rounds per story; after round three, summarize both positions in `docs/status.md` and stop.
- Report the exact commands you ran and their real output. Never claim a pass you did not observe. CI is the authority.
- Model and effort per role and per story are set in `docs/agent-routing.md`.

## Stack

Kotlin 2.0 on JVM 21 · Gradle 8.14 (wrapper, version catalog in `gradle/libs.versions.toml`) · Ktor 3 ·
PostgreSQL 16 + Flyway · Redpanda (Kafka API) · Kotest (+ property testing) · Testcontainers + Toxiproxy ·
React 18 + TypeScript + Vite (`ui/`) · GitHub Actions.

## Repo layout

```
core/      domain-neutral types: integer money, ids, DomainPlugin contract. No I/O.
ledger/    posting, idempotency (key + payload hash), reversals, outbox. Owns Flyway migrations.
calc/      ACB pools, superficial loss, FX, inbox consumer, reconciliation by replay.
ingest/    CSV parser, seeded synthetic generator.
app/       Ktor service entrypoint (wires the modules, runs migrations).
tests/     QE-OWNED. spec/ (per story), property/ (invariants), chaos/ (failures), support/, fixtures/.
ui/        React explorer.
chaos/     scripts that drive failure scenarios against the running stack.
bench/     load generator and report scripts.
docs/      build-plan, agent-workflow, adr/, stories/, problem/, bugs/, status.md, glossary.md.
```

All tests live in the `:tests` Gradle project. Production modules have no `src/test`; if a module needs a test,
QE writes it under `tests/`.

## Commands

| Task | Command |
| --- | --- |
| Compile + lint + non-integration tests | `./gradlew build` |
| Story acceptance tests | `./gradlew :tests:specTest` |
| Property tests | `./gradlew :tests:propertyTest` |
| Chaos tests (Docker) | `./gradlew :tests:chaosTest -PincludeIntegration` |
| All tests incl. Testcontainers | `./gradlew :tests:test -PincludeIntegration` |
| Lint / auto-format | `./gradlew ktlintCheck` / `./gradlew ktlintFormat` |
| Run the stack | `docker compose up -d --build --wait` then `curl localhost:8080/health` |
| UI | `cd ui && npm ci && npm run typecheck && npm run build` |
| Role check | `scripts/check-test-ownership.sh origin/main HEAD` |

`make help` lists shortcuts for all of these. Tests that need Docker must be tagged
`dev.ledgerline.testsupport.Integration`; they are skipped unless `-PincludeIntegration` is passed.

## Commits and branches

- One branch per story, named after its ID: `L-01-import-csv`.
- Every commit ends with an `Agent:` trailer: `Agent: ba`, `Agent: dev`, `Agent: qe` or `Agent: owner`.
  `scripts/check-test-ownership.sh` fails CI if a dev commit touches `tests/`, a qe commit touches production code,
  a ba commit touches anything outside `docs/`, or the trailer is missing. Run `make hooks` to check locally.
  Git only reads trailers from the **last paragraph** of the message, so put `Agent:` in the same block as any other
  trailers (`Co-Authored-By:` etc.), with no blank line between them.
- Small commits: one logical change each (one story's tests, one story's implementation, one fix, one doc update).
  Each commit compiles and passes `./gradlew build`. No "WIP" commits that bundle unrelated work.
- Owner rule (2026-11-19): after each completed change or story, commit it and push it to `main` on GitHub as well
  as the working branch. Code reaches `main` only after its story's gates pass.

## The eight invariants (each becomes a property test under `tests/property/`)

1. **Balance:** every ledger transaction's debits equal its credits, in every currency.
2. **Idempotency:** submitting the same activity twice changes nothing; the same key with a different payload is rejected.
3. **Immutability:** no ledger row is ever updated or deleted; only appended.
4. **Reversal symmetry:** applying an activity and then its reversal returns every balance to its prior value.
5. **Replay determinism:** replaying all activities in canonical order yields identical pools and identical output hashes, on any machine.
6. **Order independence of ingestion:** the same set of activities arriving in any order produces the same final state (calculation sorts by effective date, then sequence).
7. **No negative shares:** a sell cannot exceed shares held at that effective date; it is rejected with a reason, never silently clipped.
8. **Money precision:** no floating point anywhere. Cash is integer minor units; shares are fixed-point integers; pool ACB is an integer in a finer unit; one documented rounding rule, applied only at reporting boundaries.

## Money and data rules (Dev and QE)

- No `Double`, `Float` or `BigDecimal` arithmetic in `core/`, `ledger/` or `calc/`. Use `Long` minor units or fixed-point types from `core/`.
- The ledger is append-only. Corrections are a reversal plus a new activity. No `UPDATE` or `DELETE` on ledger tables.
- Calculation order is effective date, then sequence. Never arrival order.
- Synthetic data and the owner's own CSV export only. No real credentials, broker APIs or scraping.
- Tax-logic expected values must come from a published worked example, cited in the fixture. Never from agent reasoning.

## Quality gates (Definition of Done)

| Gate | Check | Threshold | Status |
| --- | --- | --- | --- |
| Tests | spec, property, integration and chaos all pass; no skipped test without a bug file | 100% | CI |
| Test integrity | role check passes | pass | CI |
| Static quality | ktlint clean; Kotlin warnings are errors; UI `tsc` strict clean | 0 issues | CI |
| Mutation score | Pitest on `ledger/` and `calc/` | **PROPOSED 70%, owner to confirm** | not wired yet |
| Reconciliation | replay diff after the story's chaos scenario | 0 drift | from step 5 |
| Performance | vs last stored benchmark in `docs/benchmarks.md` | **PROPOSED ≤10% slower, owner to confirm** | from step 9 |
| File size | max lines per Kotlin source file | **PROPOSED 400, owner to confirm** | QE review |
| Traceability | every acceptance criterion maps to a named test; tax fixtures cite sources | 100% | QE review |
| Decision record | architectural choices have an ADR written in the owner's words | — | owner |

Thresholds marked PROPOSED are placeholders until the owner sets them (step 0.1 of the workflow).

## Story template (BA writes, owner approves; one file per story in `docs/stories/`)

Copy `docs/stories/_template.md`. Required sections: title and ID, story, why it matters (link to
`docs/problem/evidence.md`), numbered acceptance criteria each with a concrete input and expected output (tax values
cite a source), out of scope, status (Draft → Approved → Tests written → In dev → In QE → Done), change notes.

## Ambiguity

If a tax rule is ambiguous: the BA flags it, the owner decides, and the decision goes in an ADR with its source.
If a story, test or ADR looks wrong: write it in `docs/status.md` under "Open questions" and stop; do not change it silently.
