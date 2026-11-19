# Status

Running log for the story loop. Every agent updates this at the end of its turn. Newest entries at the top of the log.

## Current step

**0.4 → 2: waiting on owner approval.** Steps 0.1, 0.2 (first pass) and 0.4 (all 14 stories drafted) are done.
Step 0.3 is the owner's. QE phase A for L-01 starts once the owner approves L-01.

## Stories

| ID | Title | Status | Round | Branch | Notes |
| --- | --- | --- | --- | --- | --- |
| L-01 | Import an activity CSV | Draft | – | – | split L-01a/b?; share scale; import policy |
| L-02 | Duplicate-safe ingestion | Draft | – | – | payload-hash fields, duplicate response code |
| L-03 | Balanced, immutable ledger | Draft | – | – | **sequence tie-breaker vs invariant 6 needs ADR**; immutability enforcement |
| L-04 | Corrections by reversal | Draft | – | – | reversal-of-reversal policy |
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

- **Sequence vs invariant 6 (from BA, L-03).** If "sequence" is assigned on arrival, two same-date activities order
  differently by arrival and break order independence. Proposed tie-breaker: a field from the activity itself
  (source row order, then idempotency key). Needs an ADR before L-03.
- **Outbox "sent" marker (L-05).** Marking an outbox row as sent is an `UPDATE` in the `ledger` schema; decide whether
  immutability covers the outbox or the marker lives elsewhere.

## Log

- 2026-11-19 · ba · L-06/L-07/L-08 sourced from local copies; 32 real BoC rates verified against the API.
- 2026-11-19 · ba · Evidence second pass: A-01..A-07 promoted to E-16..E-33; 'book value resets on transfer' cut.

- 2026-11-19 · orchestrator · Models and effort routed per role and story (`docs/agent-routing.md`).
- 2026-11-19 · ba · Step 0.4: all 14 stories drafted. Tax stories have no sourced values yet (network blocked).
- 2026-11-19 · ba · Step 0.2 first pass: 15 sourced claims (all GitHub), 11 labeled assumptions.

- 2026-11-19 · owner · Scaffold: Gradle multi-module build, docker compose (Postgres, Redpanda), Flyway V1, Ktor
  `/health`, QE-owned `tests/` project, role check, CI, agent files, docs skeleton.
