# Status

Running log for the story loop. Every agent updates this at the end of its turn. Newest entries at the top of the log.

## Current step

**0.4 → 2: waiting on owner approval.** Steps 0.1, 0.2 (first pass) and 0.4 (all 14 stories drafted) are done.
Step 0.3 is the owner's. QE phase A for L-01 starts once the owner approves L-01.

## Stories

| ID | Title | Status | Round | Branch | Notes |
| --- | --- | --- | --- | --- | --- |
| L-01 | Import an activity CSV | QE passed, awaiting owner | 2 | main | 36 tests, 240 generated cases; 2 bugs fixed (B-001, B-002); share scale is a placeholder (C3) |
| L-02 | Duplicate-safe ingestion | QE passed, awaiting owner | 2 | main | CI green incl. integration (run 44637937633); B-012..B-014 fixed; placeholders C20–C24 |
| L-03 | Balanced, immutable ledger | QE passed, awaiting owner | 3 | main | 83 tests (29 integration); 5 bugs, 4 fixed, B-006 open pending C15; app runs as least-privilege role |
| L-04 | Corrections by reversal | Escalated after round 3 | 3 | local `l-04` | B-008..B-010 fixed; B-011 needs owner decision C19 |
| L-05 | Reliable event publication | Draft | – | – | outbox "sent" marker vs immutability |
| L-06 | Pooled ACB: buy, sell, ROC, fees | Draft (tax) | – | – | sourced (adjustedcostbase.ca); rounding, ROC timing, negative-ACB gain timing |
| L-07 | Superficial-loss detection | Draft (tax) | – | – | sourced except AC2; adopt partial-repurchase formula? |
| L-08 | USD to CAD by trade date | Draft (tax) | – | – | real BoC rates; AC10 needs example; scale 10⁻⁴? |
| L-09 | Late correction recompute | Draft | – | – | |
| L-10 | Reconciliation by full replay | Draft | – | – | replay-hash format ADR |
| L-11 | Book value vs true ACB view | Draft | – | – | book value source |
| L-12 | Crash recovery | Draft | – | – | proposed split L-12a/b |
| L-13 | Burst benchmark and report | Draft | – | – | owner sets targets before first run |
| L-14 | Second domain plugin | Draft | – | – | which plugin |

## Owner instruction (2026-11-19)

> "Work on the stuff that doesn't need my support … where you can be ambiguous then I can fill it in properly with the
> correct info."

So stories are being **built provisionally while still in Draft**. Every open decision is a placeholder marked
`OWNER-INPUT` in code and listed in [`owner-inputs.md`](owner-inputs.md). No story is marked Approved by an agent.

## Owner decisions pending

The full structured list is in [`owner-inputs.md`](owner-inputs.md). Older items:

- [ ] Set the quality thresholds in `CLAUDE.md` (mutation score, max file length, performance tolerance).
- [ ] Rewrite ADRs 0001–0003 in your own words and set them to Accepted.
- [ ] Confirm the agent header fields in `.claude/agents/*.md` against the current Claude Code docs.
- [ ] Turn on branch protection for `main`: require the CI checks and CODEOWNERS review.
- [ ] Choose a license.
- [ ] **Approve stories** (first five: L-01, L-03, L-04, L-02, L-05). Each lists its "Needs owner decision" items.
- [x] ~~Allow network hosts~~ (done: `ledgerline` environment). Note: the agents' WebFetch still can't resolve them;
      the orchestrator fetches with curl. CRA T4037 and Wealthsimple help pages still fail (timeout / 403).
- [ ] **Decide whether adjustedcostbase.ca + TaxTips are good enough as golden sources** for L-06/L-07, or read
      CRA T4037 yourself first. Every tax number currently comes from those two sites.
- [ ] **Pitch wording:** the Book of Record comparison holds with differences (see evidence.md); ActiveACB and
      myCostBase already compare book value to ACB, so the differentiator is verifiability, not the comparison.
- [ ] Was: allow network hosts so the BA can source evidence and tax examples: `www.canada.ca`, `www.adjustedcostbase.ca`,
      `adjustedcostbase.ca`, `www.bankofcanada.ca` (also needed for the L-08 FX cache), `help.wealthsimple.com`,
      `engineering.wealthsimple.com`, `activeacb.ca`, `mycostbase.ca`. Or read the pages yourself and paste quotes.
- [ ] Step 0.3: talk to 3–5 people and add notes to `docs/problem/evidence.md`.
- [ ] Map the real Wealthsimple CSV header from your own export (header only, never commit rows).
- [ ] Approve (or not) the proposed splits L-01a/b and L-12a/b.

## Open questions

- **Follow-up after merging L-02 into l-04 (dev):** `LedgerRepository.reverse` and `correct` write only `ledger.entry`, `ledger.line` and `ledger.reversal`. They write no `ledger.activity` or `ledger.outbox` row (no test requires it; L-02's `submit` does both). A reversal therefore never reaches the L-05 outbox, and the replacement entry in `correct` has no activity row (so `findActivity` cannot see it and its key is not replayable through `submit`). Owner to decide what the outbox should carry for a reversal (an event naming the reversed entry?) before L-05. Migrations were renumbered: L-04's are now V5 (reversals) and V6 (chain guard).

- **No Docker in this sandbox:** integration suites run in GitHub CI on story branches (owner allowed pushing `l-*`). Was: **Blocked: no Docker in this sandbox** (the daemon hit the 2-hour background limit and can't be restarted).
  L-02 and L-04 integration suites can't run locally. Options: owner allows pushing `l-02`/`l-04` as branches so
  GitHub CI runs them, or a new session restarts Docker. Neither branch exists outside this container yet.

- **ESCALATED: L-04 hit round 3 (B-011).** Buy → sell → reverse sell → reverse buy: canonical order is
  buy, REV-buy, sell, REV-sell, so replay goes negative. B-009 (reversal before its original) is fixed.
  *Recommendation:* replay skips each reversed pair entirely (they net to zero by invariant 4); the ledger still keeps
  both rows for audit. *Alternative:* order a reversal by its own sequence, which breaks C17. Owner provisionally accepted
  skipping (C19, 2026-11-19); implemented as `entriesForReplay()`, B-011 Fixed pending CI; QE must point the holdings-floor check at it. L-04's code is on a **local** branch `l-04` (not pushed, gates not green).

- **Sequence vs invariant 6 (from BA, L-03).** If "sequence" is assigned on arrival, two same-date activities order
  differently by arrival and break order independence. Proposed tie-breaker: a field from the activity itself
  (source row order, then idempotency key). Needs an ADR before L-03.
- **Outbox "sent" marker (L-05).** Marking an outbox row as sent is an `UPDATE` in the `ledger` schema; decide whether
  immutability covers the outbox or the marker lives elsewhere.
- **B-003 Docker test vs the two-login design (dev, L-03 round 2, for QE).** `L03ImmutabilityEdgeSpec` "with
  docker-compose credentials, the app's role is not superuser and cannot disable it" migrates a bare container with
  the `DATABASE_USER` login. With the fix, migrations run as `DATABASE_MIGRATION_USER` and `ledgerline_app` only gets
  LOGIN after them, so the test fails on login (28P01) before it reaches its assertions. Suggested change in
  `docs/bugs/B-003-app-runs-as-database-superuser.md` (Fix note). Dev did not edit the test.
- **B-006 test vs placeholder C15 (dev, L-03 round 2, for owner and QE).** The test expects zero-amount cash activities
  to post; the C15 placeholder refuses them with `ZERO_AMOUNT`. One of them changes once the owner decides C15.
- **Docker API pin in `tests/build.gradle.kts` (dev, L-03, for QE).** Testcontainers is now 1.21.3, which negotiates
  the Docker API version. The `api.version`/`DOCKER_API_VERSION` 1.44 pin says to remove it then; it is QE-owned, so
  dev left it. L-03 integration tests pass with it in place.

## Log

- 2026-11-19 · dev · L-03 round 2: B-003, B-004, B-005, B-007 fixed; B-006 on placeholder C15 (open). Two tests
  conflict with the requested design (see Open questions). In QE.

- 2026-11-19 · dev · L-03 implemented on placeholders C3/C4; draft ADR 0005 (chart of accounts, sequence, trigger). In QE.

- 2026-11-19 · ba · L-06/L-07/L-08 sourced from local copies; 32 real BoC rates verified against the API.
- 2026-11-19 · ba · Evidence second pass: A-01..A-07 promoted to E-16..E-33; 'book value resets on transfer' cut.

- 2026-11-19 · orchestrator · Models and effort routed per role and story (`docs/agent-routing.md`).
- 2026-11-19 · ba · Step 0.4: all 14 stories drafted. Tax stories have no sourced values yet (network blocked).
- 2026-11-19 · ba · Step 0.2 first pass: 15 sourced claims (all GitHub), 11 labeled assumptions.

- 2026-11-19 · owner · Scaffold: Gradle multi-module build, docker compose (Postgres, Redpanda), Flyway V1, Ktor
  `/health`, QE-owned `tests/` project, role check, CI, agent files, docs skeleton.
