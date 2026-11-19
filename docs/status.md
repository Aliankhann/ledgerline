# Status

Running log for the story loop. Every agent updates this at the end of its turn. Newest entries at the top of the log.

## Current step

**0.1** Repo, `CLAUDE.md`, agent files, empty folders, CI skeleton. Gate: CI green on an empty test.

## Stories

| ID | Title | Status | Round | Branch | Notes |
| --- | --- | --- | --- | --- | --- |
| L-01 | Import an activity CSV | Not drafted | – | – | |
| L-02 | Duplicate-safe ingestion | Not drafted | – | – | |
| L-03 | Balanced, immutable ledger | Not drafted | – | – | |
| L-04 | Corrections by reversal | Not drafted | – | – | |
| L-05 | Reliable event publication | Not drafted | – | – | |
| L-06 | Pooled ACB: buy, sell, ROC, fees | Not drafted | – | – | |
| L-07 | Superficial-loss detection | Not drafted | – | – | |
| L-08 | USD to CAD by trade date | Not drafted | – | – | |
| L-09 | Late correction recompute | Not drafted | – | – | |
| L-10 | Reconciliation by full replay | Not drafted | – | – | |
| L-11 | Book value vs true ACB view | Not drafted | – | – | |
| L-12 | Crash recovery | Not drafted | – | – | |
| L-13 | Burst benchmark and report | Not drafted | – | – | |
| L-14 | Second domain plugin | Not drafted | – | – | |

## Owner decisions pending

- [ ] Set the quality thresholds in `CLAUDE.md` (mutation score, max file length, performance tolerance).
- [ ] Rewrite ADRs 0001–0003 in your own words and set them to Accepted.
- [ ] Confirm the agent header fields in `.claude/agents/*.md` against the current Claude Code docs.
- [ ] Turn on branch protection for `main`: require the CI checks and CODEOWNERS review.
- [ ] Choose a license.

## Open questions

_None yet._

## Log

- 2026-11-19 · owner · Scaffold: Gradle multi-module build, docker compose (Postgres, Redpanda), Flyway V1, Ktor
  `/health`, QE-owned `tests/` project, role check, CI, agent files, docs skeleton.
