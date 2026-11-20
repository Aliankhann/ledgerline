# Model and effort routing

The goal is to spend tokens where correctness is hard to get right and not elsewhere. The defaults live in each agent's
frontmatter (`.claude/agents/*.md`). The orchestrator overrides them per run using the table below. The owner can
change any row.

## Role defaults

| Agent | Model | Effort | Why |
| --- | --- | --- | --- |
| `ba` | sonnet | medium | Reading, sourcing and structured writing. The hard part is honesty about sources, which the rules enforce, not raw reasoning. |
| `qe` | sonnet | medium | Tests are the project's evidence; generators and edge cases need care. |
| `dev` | sonnet | medium | Most stories are straightforward once QE's tests define them. |

## Per-story overrides (owner rule 2026-11-20: cut usage)

**Default for every story, all phases: sonnet / medium.** The tests and CI gates catch mistakes, so a cheaper pass that
occasionally needs an extra fix round costs less than running high effort everywhere.

- **Escalate to opus / high only for round 3** of a story, and only if sonnet failed rounds 1–2 on a real defect.
- BA stays sonnet / medium. Narrow test fixes and retests can use sonnet / low.
- Keep prompts short and scoped. Agents report in a few lines.

## Other runs

| Task | Model / effort |
| --- | --- |
| Status updates, commits, merges (orchestrator) | done inline, no subagent |
| Re-sourcing evidence after hosts are allowed | `ba` default |
| Launch post drafts (step 12) | sonnet / low |
| Mutation-testing triage | sonnet / medium |
| Round 2 fixes after a QE bug | sonnet / medium |



## Orchestrator check-ins (owner rule, 2026-11-19)

Whenever tests or CI are started and the result isn't back in the same turn, the orchestrator schedules a
**10-minute check-in** that resumes the work automatically: read the result, then merge, start the next round, or
escalate. CI results on branches don't wake the session by themselves.
