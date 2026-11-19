# Ledgerline: implementation plan and the BA, Dev and QE agent workflow

A story-by-story build process where a BA agent defines the work, a QE agent writes and attacks the tests, a Dev agent implements, and you stay the decision-maker.

Nov 19, 2026 · @Alian Khan

## My verdict and the changes I made

The BA, Dev and QE split is a good plan, and the QE-to-Dev back-and-forth is the strongest part. I kept it, with six changes that fix where it would otherwise break.

| # | Change | Why |
| --- | --- | --- |
| 1 | **You are the fourth role: owner and decision-maker.** | You will be asked to defend every decision in interviews and replies. If agents make the calls, you can't. Agents propose; you decide the ADRs and approve each story. |
| 2 | **The BA cannot prove traction; it can only organize evidence.** | An LLM can't tell you people want this. The BA collects public evidence, writes stories and defines launch hypotheses. You run 3 to 5 real conversations with Canadian investors or engineers. |
| 3 | **QE writes tests from the story before Dev writes code.** | If QE reads the code first, both share the same blind spots. Spec-first tests catch what Dev misunderstood. QE then attacks the finished code as a second pass. |
| 4 | **Dev may not edit QE's tests.** | Otherwise a failing test gets "fixed" by weakening it. A disputed test goes to you. |
| 5 | **Cap the back-and-forth at three rounds per story.** | Agents can loop on nitpicks forever. After round three, escalate to you with both positions summarized. |
| 6 | **Quality is measured, not just asserted.** | "High quality" needs numbers: mutation score, property-test counts, reconciliation drift of zero, lint and type checks clean, benchmark results stored. |

**Two things to keep in mind:**

- **Test-writer bias:** QE and Dev are the same underlying model unless you configure otherwise, so they can share mistakes. Source expected values for tax logic from published worked examples, not from either agent's reasoning.
- **Pace:** a story loop adds overhead. Keep stories small (half a day or less) or the 12-day schedule slips.

## Roles

| Role | Owns | Receives | Produces | Must not |
| --- | --- | --- | --- | --- |
| **BA agent** | The problem, the users and the stories | Project pitch, public sources, your notes from real conversations | Problem-evidence log, personas, 14 or fewer user stories with acceptance criteria, launch hypotheses | Invent user quotes or traction numbers; write code; add stories without your approval |
| **QE agent** | Whether the work is correct and robust | An approved story | Tests written from the story first; then an attack report on the finished code; bug files with reproduction steps | Edit production code; accept "it works on my machine"; raise style nitpicks as blockers |
| **Dev agent** | Implementation and code organization | Approved story plus QE's failing tests | Small commits that make tests pass, refactors, a short change note | Edit or delete QE's tests; add features beyond the story; hide a failing test |
| **You** | Decisions, priorities and the narrative | Everything above | Approvals, ADRs, resolutions of disputes, the demo and posts | Let any agent merge without a gate passing |

**Authority rules:**

- **BA sets "what and why".** If Dev or QE thinks a story is wrong, they flag it to the BA and you, not silently change it.
- **QE has a veto on "done".** A story is not done until QE signs off and the gates pass.
- **You settle every dispute** after round three, or any time a decision affects architecture, scope or the public narrative.

## The loop

&#91;embedded content: story loop · 4 roles, 1 retest loop\]

Each story goes through approval, spec-first tests, implementation and attack. A failed attack sends it back to Dev for up to three rounds before it reaches you.

## Handoff files and repo conventions

Agents hand off through files in the repo, not chat memory. That makes the process auditable, and you can show it as part of the project.

```
ledgerline/
  CLAUDE.md                     # shared rules: stack, commands, conventions
  .claude/agents/               # ba.md, dev.md, qe.md (definitions below)
  docs/
    problem/evidence.md         # BA: sourced evidence the problem is real
    problem/personas.md         # BA: who this is for, in two or three lines each
    stories/L-01-import-csv.md  # BA: one file per story
    adr/0001-....md             # you: decisions, drafted by agents
    bugs/B-001-....md           # QE: reproduction steps, severity, status
    status.md                   # running log: story, state, round number
  tests/spec/                   # QE-owned: written from stories, before code
  tests/property/               # QE-owned: invariants and generators
  tests/chaos/                  # QE-owned: failure scenarios
```

**Story file format** (the BA fills it, you approve it):

1. **Title and ID** (for example, L-06 Pooled ACB for buy and sell).
2. **Story:** As a self-directed investor / As a reviewing engineer, I want ..., so that ...
3. **Why it matters:** one line linking to the evidence log.
4. **Acceptance criteria:** numbered, each testable, each with a concrete example input and expected output. Expected values cite a source for any tax logic.
5. **Out of scope** for this story.
6. **Status:** Draft, Approved, Tests written, In dev, In QE, Done.

**Branches and merging:** one branch per story, named after its ID. Dev commits to the branch; QE's tests live in the same branch but only QE's agent edits files under `tests/`. A story merges to main only when every gate in this document passes and you approve. Use a CODEOWNERS file or a pre-commit check to enforce the test-ownership rule.

## Story backlog

This is the starting backlog the BA agent should refine, not a final list. Each story is sized for half a day or less. Acceptance headlines are the first criterion QE turns into a test.

| ID | Story (short) | Acceptance headline | Day |
| --- | --- | --- | --- |
| L-01 | Import an activity CSV into canonical activities | Your own export and the seeded generator both parse; bad rows are rejected with a row number and reason | 2 |
| L-02 | Duplicate-safe ingestion | Same key twice is a no-op; same key with a different payload is rejected | 4 |
| L-03 | Balanced, immutable ledger | Debits equal credits in every currency; no update or delete is possible | 3 |
| L-04 | Corrections by reversal | Apply then reverse returns every balance to its prior value | 3 |
| L-05 | Reliable event publication | Killing the service between database write and publish loses or duplicates nothing | 4 |
| L-06 | Pooled ACB for buy, sell, return of capital and fees | Matches a cited worked example to the cent | 5 |
| L-07 | Superficial-loss detection across accounts | Matches cited examples, including a repurchase in a registered account | 6 |
| L-08 | USD to CAD by trade date | Weekend and holiday trades use the prior business day's rate | 7 |
| L-09 | Late correction recompute | A correction dated months back recomputes affected pools and nothing else | 8 |
| L-10 | Reconciliation by full replay | Replay equals live pools; any difference fails with a report | 8 |
| L-11 | Book value versus true ACB view with reasons | Every difference shows an entry-level explanation | 10 |
| L-12 | Crash recovery | Kill the consumer mid-batch; after restart, reconciliation shows zero drift | 5, 9 |
| L-13 | Burst benchmark and report | One command produces a table with hardware and seed | 9 |
| L-14 | Second domain plugin | A payments-balance or position tracker runs on the same core | 10 |

The BA's first task, before any story is approved, is the **problem evidence log**: sourced links showing that cost-basis mismatch is a real problem for Canadian investors, the existing tools and their gaps, who would care about this project (self-directed investors, reviewing engineers), and what "traction" means here. Suggested traction signals, set as hypotheses and not promises: replies from working engineers, repository stars and forks, interview or conversation requests, and the number of people who run the quickstart successfully.

## Step-by-step implementation plan

Each step names its owner and its gate. Do not start a step until the previous gate has passed. Day numbers match the 12-day plan in the build-plan doc.

| Step | Day | What happens | Owner | Gate |
| --- | --- | --- | --- | --- |
| 0.1 | 0 | Create repo, `CLAUDE.md`, agent files, empty folders, CI skeleton | You | CI green on an empty test |
| 0.2 | 0 | BA builds the problem evidence log and personas | BA, then you review | You can state in two sentences who this is for and what evidence supports it |
| 0.3 | 0 | You talk to 3 to 5 real people (Canadian investors or engineers) and add notes | You | Notes added; BA revises the log; any claim without a source is cut |
| 0.4 | 0 | BA drafts stories L-01 to L-14 from the backlog | BA, then you approve | Every story has testable criteria; you approve the first five |
| 1 | 1 | Docker compose, glossary, first ADRs (idempotency, money types, reversals) | Dev drafts, you decide | Three ADRs in your words; `docker compose up` works |
| 2 | 2 | L-01: QE writes parser tests and fixtures first; Dev implements | QE, then Dev | Spec tests pass; seeded generator is deterministic |
| 3 | 3 | L-03 and L-04: ledger and reversals; define the `DomainPlugin` interface | QE tests first, Dev builds | Property tests for balance, immutability, reversal symmetry pass |
| 4 | 4 | L-02 and L-05: idempotent funnel, outbox, Kafka publish | QE, Dev | Duplicate and crash-between-write-and-publish tests pass |
| 5 | 5 | L-06 and first L-12: consumer with transactional inbox, basic pools | QE, Dev | Replay twice gives identical hashes; kill-and-restart passes |
| 6 | 6 | L-07: superficial loss against cited examples | QE sources fixtures; Dev builds | All cited examples match exactly; edge cases not modeled are listed |
| 7 | 7 | L-08: FX cache and prior-business-day rule | QE, Dev | Weekend and holiday fixtures pass |
| 8 | 8 | L-09 and L-10: late correction and reconciliation | QE, Dev | Reconciliation zero drift after a late correction |
| 9 | 9 | L-12 chaos suite, L-13 benchmark, differential test against an open-source calculator | QE leads | Chaos scenarios scripted; benchmark report generated by one command |
| 10 | 10 | L-11 UI and L-14 second plugin | QE, Dev | Every on-screen difference has an explanation; plugin passes the core's tests |
| 11 | 11 | Mutation testing pass, clean-VM run, README, ADRs finished, record demo | QE, you | Fresh clone runs the demo end to end |
| 12 | 12 | Launch prep: posts, tags, video captions | BA drafts, you finalize | You have read every claim and can back it with a test or a number |

**Per-story loop (steps 2 to 10):** BA story approved by you; QE writes failing spec tests; Dev makes them pass; QE attacks the result (property tests, edge cases, chaos) and files bugs; Dev fixes; repeat up to three rounds; you approve the merge.

## Quality gates and definition of done

A story is done only when every gate passes. Automate as many as possible in CI so no agent can claim a pass.

| Gate | Check | Owner |
| --- | --- | --- |
| Tests | All spec, property, integration and chaos tests pass; no skipped tests without a bug file | CI |
| Test integrity | No commit by Dev touches `tests/` | CI check |
| Static quality | Linter and formatter clean; compiler or type check has no warnings treated as errors | CI |
| Mutation score | Core packages (`ledger/`, `calc/`) meet a score you set on day 1 and raise over time; report the number | QE |
| Reconciliation | Replay diff is zero after the story's chaos scenario | CI |
| Performance | No more than a set percentage slower than the last stored benchmark | CI |
| Code organization | No file over a set length; no duplicated logic QE can point to; public functions documented; modules follow the repo layout | QE reviews, Dev fixes |
| Traceability | Every acceptance criterion maps to a named test; every tax-logic fixture cites its source | QE |
| Decision record | Any architectural choice has an ADR in your words | You |
| Comprehension | You can explain the change and its trade-off without reading it | You |

Set the numeric thresholds (mutation score, file length, performance tolerance) on day 1 and record them in `CLAUDE.md`. Choose values you can defend; don't copy numbers from elsewhere without checking they fit this code.

## Agent definitions

These follow the Claude Code subagent file format (a markdown file with name, description and tools in the header, then the instructions). Save each under `.claude/agents/`. Check the current Claude Code documentation for the exact header fields before you rely on them, since they can change.

**`.claude/agents/ba.md`**

```markdown
---
name: ba
description: Business analyst for Ledgerline. Use to build the problem evidence log, personas, user stories and launch hypotheses. Never writes code.
tools: Read, Write, Edit, WebSearch, WebFetch
---
You are the business analyst for Ledgerline, a portfolio project that shows how to build a verifiable cost-basis system on an event-sourced ledger.

Your job:
1. Keep docs/problem/evidence.md: sourced links showing the problem is real, existing tools and their gaps, and who cares. Every claim has a URL. If you cannot source it, mark it as an assumption.
2. Write stories in docs/stories/ using the template in CLAUDE.md. Each story has testable acceptance criteria with concrete example inputs and outputs. Tax logic expected values must cite a source.
3. Propose launch hypotheses and traction signals. Label them hypotheses.

Rules:
- Never invent user quotes, statistics or traction numbers.
- Never write or edit code or tests.
- Keep stories small: half a day or less. Maximum 14 stories unless the owner approves more.
- Flag stories that depend on tax judgment as needing an explicit owner decision.
- Say plainly when evidence is thin.
```

**`.claude/agents/qe.md`**

```markdown
---
name: qe
description: Quality engineer for Ledgerline. Use to write tests from a story before implementation, then to attack the implementation and file bugs. Never edits production code.
tools: Read, Write, Edit, Bash, Grep, Glob
---
You are the quality engineer for Ledgerline.

Phase A, before code: read the approved story and write failing tests under tests/ that cover every acceptance criterion, using only the story as your source. Add property-based tests for the invariants in CLAUDE.md. Tax-logic fixtures must cite their published source.

Phase B, after code: attack the implementation. Try duplicates, reordering, boundary dates, huge and tiny values, rounding, concurrency, crashes and replays. Run mutation testing on the core packages and report the score. File each defect in docs/bugs/ with reproduction steps, expected versus actual, and severity.

Rules:
- Never edit production code. You own everything under tests/.
- Do not block on style preferences; block on correctness, robustness, performance regressions and clear organization problems you can point to.
- Maximum three rounds per story. After round three, summarize both positions for the owner.
- Say exactly what you ran and what you did not run. Never claim a pass you did not observe.
```

**`.claude/agents/dev.md`**

```markdown
---
name: dev
description: Developer for Ledgerline. Use to implement one approved story at a time so that the QE's tests pass. Never edits tests.
tools: Read, Write, Edit, Bash, Grep, Glob
---
You are the developer for Ledgerline. Implement exactly one approved story at a time.

Process: read the story and the failing tests; make them pass with the simplest clean design; refactor; run the full suite, linter and type checks; write a short change note in the story file.

Rules:
- Never edit or delete anything under tests/. If a test looks wrong, write a note for the owner and the QE instead.
- Do not add features beyond the story.
- Follow the repo layout and the money rules in CLAUDE.md: integer minor units, no floating point, append-only ledger.
- Put decisions that affect architecture in a draft ADR for the owner to rewrite. Do not make them silently.
- Report what you ran and the actual results.
```

Put the shared facts (stack, commands, invariants, thresholds, story template) in `CLAUDE.md` so all three agents read the same source.

## Failure modes and guardrails

| Failure mode | What it looks like | Guardrail |
| --- | --- | --- |
| **Agents agree too easily** | QE approves Dev's work because both reason alike | Spec-first tests from the story; cited external fixtures for tax logic; mutation testing; differential test against an open-source calculator |
| **Test gaming** | Dev weakens or deletes a failing test | Dev has no write access to `tests/`; CI fails if a Dev commit touches it |
| **Endless loops** | QE keeps raising minor points | Three-round cap; QE blocks only on correctness, robustness, regressions and clear organization defects |
| **Scope creep** | Dev adds features; BA adds stories | One story at a time; story count capped; every change needs your approval |
| **Invented evidence** | BA states a statistic or user need with no source | Every claim needs a URL or is labeled an assumption; you re-check the sources |
| **False confidence** | An agent says "all tests pass" without running them | Agents report commands and outputs; CI is the authority, not the agent's summary |
| **Lost context** | An agent forgets decisions from earlier days | Handoff files, `status.md`, ADRs and `CLAUDE.md` hold the state |
| **You can't explain it** | The demo is great, but you stumble on questions | Comprehension gate on every story; rewrite ADRs in your words; practice the "why not X?" answers |
| **Cost and time overrun** | Many agent runs per story | Small stories, narrow task prompts, cheaper model for BA drafting if quality holds, and a daily time box |

**Also decide up front:** what happens when a tax rule is ambiguous. The default is that the BA flags it, you decide, and the decision goes in an ADR with the source.

## Your role and the kickoff checklist

The agents speed up the work; you carry the project. In an interview or a reply thread, the questions go to you.

**Your four jobs:**

1. **Decide.** Approve stories, settle disputes and write the ADRs in your own words.
2. **Verify.** Run the demo yourself. Open a few tests and mutate the code by hand to see them fail.
3. **Talk to humans.** Do the 3 to 5 real conversations; no agent can substitute for them.
4. **Own the story.** Write the final posts and README intro yourself; agents can draft, but the voice and the claims are yours.

**Kickoff checklist (do today):**

- [ ] Create the repo and add `CLAUDE.md` with stack, commands, the eight invariants and the story template.
- [ ] Save the three agent files under `.claude/agents/` and confirm the header fields against current documentation.
- [ ] Set the numeric quality thresholds and record them in `CLAUDE.md`.
- [ ] Set up the CI check that blocks Dev commits touching `tests/`.
- [ ] Run the BA on the problem evidence log and read every source it cites.
- [ ] Message 3 to 5 people (Canadian investors or engineers) for a short conversation.
- [ ] Approve the first five stories and start step 1.

**Daily rhythm:** morning, approve the day's story and read yesterday's status; midday, review the QE's first attack report; evening, run the gates yourself, rewrite any ADR in your words, and note one thing you could explain in an interview.
