# Model and effort routing

The goal is to spend tokens where correctness is hard to get right and not elsewhere. The defaults live in each agent's
frontmatter (`.claude/agents/*.md`). The orchestrator overrides them per run using the table below. The owner can
change any row.

## Role defaults

| Agent | Model | Effort | Why |
| --- | --- | --- | --- |
| `ba` | sonnet | medium | Reading, sourcing and structured writing. The hard part is honesty about sources, which the rules enforce, not raw reasoning. |
| `qe` | sonnet | high | Tests are the project's evidence; generators and edge cases need care. |
| `dev` | sonnet | medium | Most stories are straightforward once QE's tests define them. |

## Per-story overrides

The rule of thumb is to escalate to **opus / high** where a subtle bug would silently break an invariant: concurrency,
crash-safety, ordering, and money math that compounds. Use sonnet everywhere else.

| Story | QE phase A (spec tests) | Dev | QE phase B (attack) | Reason |
| --- | --- | --- | --- | --- |
| L-01 CSV import + generator | sonnet / medium | sonnet / medium | sonnet / medium | Parsing and seeded determinism; well-trodden |
| L-02 Duplicate-safe ingestion | sonnet / high | sonnet / high | sonnet / high | Canonical payload hashing must be stable |
| L-03 Balanced, immutable ledger + DomainPlugin | opus / high | opus / high | opus / high | Core schema and interface everything else builds on |
| L-04 Corrections by reversal | sonnet / high | sonnet / high | sonnet / high | Builds directly on L-03 patterns |
| L-05 Reliable publication (outbox) | opus / high | opus / high | opus / high | Crash windows between DB and Kafka |
| L-06 Pooled ACB | opus / high | sonnet / high | opus / high | Expected values must match sourced examples to the cent |
| L-07 Superficial loss | opus / high | opus / high | opus / high | Windowed, cross-account, proration |
| L-08 USD→CAD FX | sonnet / medium | sonnet / medium | sonnet / high | Lookup rules plus a cache |
| L-09 Late correction recompute | opus / high | opus / high | opus / high | Recompute-from-date must equal a full replay |
| L-10 Reconciliation by replay | sonnet / high | opus / high | sonnet / high | Deterministic hashing across runs |
| L-11 Book value vs ACB view | sonnet / medium | sonnet / medium | sonnet / medium | UI over existing data |
| L-12 Crash recovery / chaos | opus / high | opus / high | opus / high | Inbox + offset atomicity under failure |
| L-13 Burst benchmark | sonnet / medium | sonnet / medium | sonnet / medium | Harness and report |
| L-14 Second plugin | sonnet / medium | sonnet / medium | sonnet / medium | Reuses the core contract |

## Other runs

| Task | Model / effort |
| --- | --- |
| Status updates, commits, merges (orchestrator) | done inline, no subagent |
| Re-sourcing evidence after hosts are allowed | `ba` default |
| Launch post drafts (step 12) | sonnet / low |
| Mutation-testing triage | sonnet / medium |
| Round 2–3 fixes after a QE bug | same as the story's Dev row; do not escalate unless round 3 |

If a story hits round 3 on sonnet, the final round runs on opus before it goes to the owner.
