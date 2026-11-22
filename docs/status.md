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
| L-04 | Corrections by reversal | QE passed, awaiting owner | 3+C19 | main | CI green incl. integration (run 44648004200); B-008..B-011 fixed; C19 provisional (replay skips reversed pairs) |
| L-05 | Reliable event publication | QE passed, awaiting owner | 2 | main | CI green incl. 21 outbox/attack tests (run 44658644992); B-015..B-017 fixed; placeholders C5, C25–C27 (parked events block their taxpayer until owner decides) |
| L-06 | Pooled ACB: buy, sell, ROC, fees | QE passed, awaiting owner | 2 | main | CI green (run 44665217794); B-018 fixed; tax values provisional CA-ITA-2026.1 |
| L-07 | Superficial-loss detection | QE passed, awaiting owner | 1 | main | CI green (run 44665217794); AC2 skipped pending sourced example (A2); SL-PARTIAL provisional |
| L-08 | USD to CAD by trade date | QE passed, awaiting owner | 2 | main | CI green (run 44678105986); real BoC rates; B-019, B-020 fixed; AC10 skipped pending sourced example; scale 10^-4 provisional |
| L-09 | Late correction recompute | QE passed, awaiting owner | 1 | main | CI green (run 44681018201); AC6–AC8 integration parts pending; outputHash still from full replay (follow-up) |
| L-10 | Reconciliation by full replay | QE passed (pure part), awaiting owner | 1 | main | CI green (run 44683743890); DB/CLI ACs pending; hash scope is owner decision C28 |
| L-11 | Book value vs true ACB view | QE passed (backend), awaiting owner | 2 | main | CI green (run 44687695210); B-021..B-023 fixed; UI table + live ledger-to-pool source are follow-ups; UNEXPLAINED in rule set and C29 are owner questions |
| L-12 | Crash recovery | L-12a QE passed, awaiting owner | 2 | main | CI green incl. crash + reversal integration (run 44697235379); B-024 fixed; follow-ups: Toxiproxy AC5, AC8, AC9, wire consumer into app/compose; ADR 0006 draft |
| L-13 | Burst benchmark and report | Harness done, awaiting owner | 1 | main | CI green incl. smoke (run 44700919782); targets C10 and hardware B3 are owner inputs; uncovered ACs listed in story |
| L-14 | Second domain plugin | QE passed (pure), awaiting owner | 1 | main | CI green (run 44701421807); cash-balance plugin in plugins/; C30 overdraft; PluginReplay location (calc vs core) owner call; AC1/6/7 untested |
| L-15 | Differential ACB check against tsiemens/acb | Tests written + exporter built; CI run 45060148937: 371 tests, only the 3 L15Diff tests red (tax-rule differences, owner) | 1 | l-15 | Extra story beyond the 14-story cap (owner approval); oracle pinned to acb ee4afc7 (0.26.06); SL-PARTIAL agrees; see Open questions |

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

- **CRA mutual-fund redemption differs by 13 cents (rounding rule, owner decision).** `CraFixtureSpec` (CRA tax-treatment-mutual-funds page):
  CRA multiplies 200 units by the per-unit ACB rounded to the cent ($15.20) → ACB sold $3,040.00, gain $374.00. Ledgerline pools unrounded
  and rounds only at reporting (`ROUND-BOUNDARY`) → ACB sold $3,039.87, gain $374.13. The test asserts our value and bounds the gap.
  Keep ours (more precise; CRA's figure is an illustration) or round per-unit ACB to the cent before multiplying (new rule-set version).

- **Owner research arrived 2026-11-22** (`docs/problem/sources-2026-11-22.md`; quotes as reported by the owner's LLM, not
  re-fetched by the BA). It informs three open owner decisions: (1) `SL-MULTI`: TI 2004-0073011E5 computes the loss per
  disposition with P = all identical items acquired in the 61-day window, with no one-to-one matching, and tsiemens/acb
  reuses repurchases (L-15 seed 22), so our one-sale-per-share convention differs from both; (2) rounding order
  (`ROUND-BOUNDARY`): CRA examples do not settle it, tsiemens/acb rounds the net gain after the denial; (3)
  registered-account denial (`SL-REGISTERED`): CRA says nothing about a TFSA/RRSP repurchase, so it stays on secondary
  sources, and tsiemens/acb goes beyond the TI. `DATE-BASIS` stays an owner choice (CRA silent; tsiemens/acb uses
  settlement date). CRA-sourced fixtures are now available for QE: STU (identical properties), XYZ (gain with
  commission), mutual fund redemption, ROC to zero ACB, and TI 2004-0073011E5 (S=50, P=20, B=20; no prices, so QE must
  state the prices as an assumption). Citations are in `docs/tax-model.md`.

- **L-10 `reconcile` command merged** (CI run 45061631621 green incl. 11 Docker tests and the compose reconcile step). AC6 (two JVMs), AC7 (three stacks) and AC8 (watermark, owner decision) remain uncovered.

- **L-15 differential check vs tsiemens/acb disagrees on tax rules (owner decision; not patched).** On seeds 1..200
  (branch `l-15`, `:tests:propertyTest --tests '*L15Diff*' -PincludeIntegration`): AC2 final ACB differs in 11 histories,
  AC3 gain per sell in 56 (mostly 1 cent), AC4 superficial-loss denial in 24. Known causes: (1) **rounding order**: acb
  rounds the net gain after the denial; our `ROUND-BOUNDARY` rounds loss and denial separately (seed 6: -59.11 vs -59.10);
  (2) **`SL-MULTI`**: acb lets the same repurchased shares deny two loss sales; we match each share to one sale (seed 22);
  (3) **`SL-ADDBACK-TIMING`**: acb adds the denied loss on the sale date. Remaining larger AC4 gaps are not root-caused.
  Partial proration (`SL-PARTIAL`, least(S,P,B)/S) agrees. Decide per rule: keep ours (then L-15 records an accepted
  difference list), or change the rule set (new version, never edit CA-ITA-2026.1). Sources requested from the owner.

- **Live holdings now use `replaySuperficial`; the consumer's `calc.pool` and `Reconciler` still use plain `replay`** (L-11 follow-up, C31). On a superficial-loss fixture the route's ACB differs from the reconciler's by the denied loss. Owner: move consumer + reconciler to `replaySuperficial` too (recommended, makes all three agree), or revert the route.

- **Holdings route lists only securities in the book-value CSV.** Pools without a book value are hidden. Should it list all pools (book value shown as missing)? (owner)
- **New relay/consumer logs use System.Logger (JUL), not logback.** Add an slf4j/JUL bridge so all logs share one format. (Dev follow-up)
- **End-to-end pipeline is live** (`scripts/demo.sh`, checked in CI's compose job): POST → ledger → outbox → Redpanda → consumer → pools → holdings route.



- **Follow-up after merging L-02 into l-04 (dev):** `LedgerRepository.reverse` and `correct` write only `ledger.entry`, `ledger.line` and `ledger.reversal`. They write no `ledger.activity` or `ledger.outbox` row (no test requires it; L-02's `submit` does both). A reversal therefore never reaches the L-05 outbox, and the replacement entry in `correct` has no activity row (so `findActivity` cannot see it and its key is not replayable through `submit`). Owner to decide what the outbox should carry for a reversal (an event naming the reversed entry?) before L-05. Migrations were renumbered: L-04's are now V5 (reversals) and V6 (chain guard). *L-05 (dev):* reversals and corrections now write `ENTRY_REVERSED` outbox rows (placeholder C25); the replacement still has no activity row.

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
  immutability covers the outbox or the marker lives elsewhere. *L-05 (dev):* built provisionally as the append-only `ledger.outbox_delivery` table (C5).
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

- 2026-11-20 · dev · L-05 round 2: B-015, B-016, B-017 fixed pending CI (serial sends per taxpayer, `EVENT_TOO_LARGE` at
  ingestion, V8 `ledger.outbox_parked`, placeholder C27). In QE.

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

- **L-11 live route has no pool source (dev, round 2):** `Main.kt` wires `acbVsBookModule` with an empty pool map and `BOOK_VALUE_CSV`. Owner to decide how the service builds pools from the ledger (C29). Also, `UNEXPLAINED` was added to `CaIta2026v1.ruleIds` only because the L-11 tests require every reason ID to be in that set; QE/owner may prefer to relax the test.
