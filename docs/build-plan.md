# Ledgerline: build plan for a mini Book of Record

A 12-day plan to build, benchmark and publicly demo an event-sourced double-entry ledger with a streaming cost-basis engine, using synthetic data.

Nov 19, 2026 · @Alian Khan

## Pitch

Ledgerline is a small financial-ledger system built the way real brokerages and payment companies build theirs: every change enters through one idempotent funnel, lands in an append-only double-entry ledger, and flows over a stream into a calculation layer that is continuously checked against a full replay. On top sits a Canadian adjusted-cost-base (ACB) engine, so the demo answers a real question: what did I actually pay for this holding, once superficial losses, FX and return of capital are counted?

**Why it resonates with all three companies:**

- **Wealthsimple:** it mirrors their published Book of Record design (one validated funnel, double-entry ledger, separate streaming calculation path). Their own help pages say broker book value isn't guaranteed to match true ACB, which is the gap Ledgerline closes.
- **Stripe:** idempotency keys, exactly-once effects, immutable ledger entries and reconciliation are the core of how payment infrastructure earns trust.
- **Robinhood:** correct positions and cost basis under bursty load, especially at the market open, are the hard part of a retail brokerage.

**What makes it worth posting:** it isn't a feature clone. ACB calculators already exist, so the differentiator is proof of engineering rigor, shown in a short demo: replay determinism, duplicate-safe ingestion, crash recovery with no drift, late corrections that recompute cleanly, and measured throughput.

**Definition of done (all must be true before you post):**

1. `docker compose up` starts everything and loads seeded synthetic data.
2. Replaying the same year of activity twice produces byte-identical output hashes.
3. A reconciliation job reports zero drift between the live view and a full ledger replay, including after a mid-stream consumer crash.
4. A property-based test suite passes on ledger invariants and ACB math.
5. A benchmark report with your measured numbers is in the repo.
6. A 3-minute demo video and a README with architecture and decision records are published.

### Differentiator: a cost-basis number you can verify

Existing ACB calculators take trades in and give a number out. Ledgerline's differentiator is that every number is **traceable, replay-verified and safe under failure**. Concretely, a user and an engineer each get something the free tools don't give:

- **For the user:** every ACB figure links to the ledger entries behind it; differences from the broker's book value come with a plain-language reason; late corrections and duplicate uploads don't corrupt the result.
- **For the engineer:** the replay hash, the reconciliation report and the crash-recovery demo prove the numbers are exact.

The product is a better-engineered cost-basis tool, but the pitch is the engineering. Don't claim it is more accurate than existing calculators unless your differential test against an open-source calculator (see Testing) shows it.

**Plug-in structure.** Keep the ledger, funnel, outbox and reconciliation domain-neutral. Define a small `DomainPlugin` interface (validate an activity, apply it to state, explain a result). Ship ACB as plugin one. Add a tiny second plugin, such as a payments-balance tracker (Stripe-style) or a simple position and average-price tracker (Robinhood-style), to show the core is reusable. Define the interface on day 3 and build the second plugin on day 10; drop it first if the schedule slips.

## Scope

Depth beats breadth. Ship fewer features with real proof behind each.

| In scope | Out of scope |
| --- | --- |
| Activity types: buy, sell, dividend, return of capital, fee, deposit, withdrawal, transfer-in, FX conversion | Options, futures, crypto, margin |
| Currencies: CAD and USD, with the Bank of Canada daily rate on each trade date | Other currencies |
| Pooled average ACB per security across all of a taxpayer's non-registered accounts | Tax filing, forms, or advice |
| Superficial-loss detection with a 30-day window before and after, counting purchases in TFSA and RRSP | Spousal and affiliated-person edge cases beyond a documented flag |
| Append-only double-entry ledger, reversals for corrections | Real payments, real brokers, real money |
| Kafka event stream, idempotent consumer, reconciliation by replay | Multi-region, auth, user management |
| Input: Wealthsimple-style activity CSV plus a synthetic generator | Any real Wealthsimple, Stripe or Robinhood credentials, APIs or scraping |
| React page: book value versus true ACB, with a reason for each difference | Mobile app |

The Wealthsimple activity-statement CSV is web-only and capped at 1,000 activities per download, so build your parser against that shape but verify the real column names from your own export before you finalize it.

## Architecture

&#91;embedded content: architecture · 8 stages, 1 reconciliation loop\]

Activities enter once through the funnel and become ledger entries; an outbox publishes them to the stream, the calculator builds ACB pools, and reconciliation replays the ledger to prove the pools are exact.

## Domain model and invariants

The invariants are what you test and what you talk about. Write each as a property-based test.

| Concept | Definition |
| --- | --- |
| Activity | Canonical, validated business event with an idempotency key, taxpayer, account, security, effective date, recorded-at time, and amounts |
| Ledger entry | One balanced set of debit and credit lines per activity, append-only, never edited |
| Reversal | A compensating entry that cancels a prior one; a correction is a reversal plus a new activity |
| Pool | Running totals of shares and total ACB for one taxpayer and one security, computed in effective-date order |
| Adjustment | A change to ACB that is not a trade: return of capital, denied superficial loss added back, fees |

**Invariants:**

1. **Balance:** every ledger transaction's debits equal its credits, in every currency.
2. **Idempotency:** submitting the same activity twice changes nothing; the same key with a different payload is rejected.
3. **Immutability:** no ledger row is ever updated or deleted; only appended.
4. **Reversal symmetry:** applying an activity and then its reversal returns every balance to its prior value.
5. **Replay determinism:** replaying all activities in canonical order yields identical pools and identical output hashes, on any machine.
6. **Order independence of ingestion:** the same set of activities arriving in any order produces the same final state, because calculation sorts by effective date then sequence.
7. **No negative shares:** a sell cannot exceed shares held at that effective date (rejected with a reason, never silently clipped).
8. **Money precision:** no floating point anywhere. Cash is integer minor units; shares are fixed-point integers; pool ACB is held as an integer in a finer unit (for example millionths of a cent), with one documented rounding rule applied only at reporting boundaries.

**Superficial loss rule used (state this in the README):** a loss on a sale is denied if identical property is bought in the 30 days before or after the sale and some of it is still held 30 days after. The denied portion is added to the ACB of the repurchased shares, or permanently lost if the repurchase was in a registered account. This is a simplified model, not tax advice; list the edge cases you did not model.

## Stack and why

Chosen to match what Wealthsimple lists for its Book of Records and streaming teams (Java or Kotlin, Kafka, Postgres, React with TypeScript), while using tools Stripe and Robinhood engineers will also recognize.

| Layer | Choice | Reason |
| --- | --- | --- |
| Language | Kotlin (TypeScript is the fallback if you're faster there) | Matches the Book of Records stack; strong types for money and state. Pick the language you can move fastest in. |
| Service framework | Ktor or Spring Boot | Either is fine; use what you know |
| Database | PostgreSQL | Transactions, constraints and row locks give you the ledger guarantees directly |
| Migrations | Flyway | Versioned schema, reviewable |
| Stream | Redpanda (Kafka API) in Docker | Single binary, Kafka-compatible, fast to start |
| Consumer | Plain Kafka consumer with a transactional inbox | Simpler and easier to prove correct than a stream-processing framework; add Flink only as a stretch |
| Tests | Kotest property testing, Testcontainers, Toxiproxy | Property tests for invariants; real Postgres and Kafka; fault injection |
| Load | k6 or a Kotlin load generator | Reproducible throughput numbers |
| Observability | OpenTelemetry traces and Prometheus metrics, Grafana dashboard | Makes the demo visual and shows lag and drift |
| UI | React, TypeScript, Vite | Thin reconciliation and ACB explorer |
| CI | GitHub Actions | Tests and benchmark smoke run on every push |
| FX rates | Bank of Canada Valet API, cached to a local file | Free and keyless; cache so demos work offline |

Use synthetic data and your own exported CSV only.

## Design decisions and rejected alternatives

Write each as a half-page decision record in `/docs/adr`. In interviews and replies, lead with the rejected alternative; it shows judgment.

| Decision | Chosen | Rejected, and why |
| --- | --- | --- |
| Corrections | Reversal entry plus a new activity | Editing rows in place: destroys audit history and breaks replay |
| Idempotency | Unique key per activity, with a payload hash stored beside it | Key alone: lets a changed payload under the same key slip through silently |
| Consumer delivery | At-least-once delivery plus an idempotent consumer; offsets and state change committed in one Postgres transaction | Kafka exactly-once transactions across systems: more moving parts and still needs idempotent writes at the database edge |
| Publishing events | Transactional outbox table drained to Kafka | Writing to Postgres then Kafka separately: a crash between them loses or duplicates events |
| Partition key | Taxpayer ID | Security ID: a taxpayer's cross-account superficial-loss check would span partitions |
| Ordering | Calculate by effective date then sequence, not arrival time | Processing order: late or out-of-order activity would give different answers on replay |
| Late corrections | Recompute the affected taxpayer and security pool from the earliest changed date forward | Patching totals incrementally: fast but easy to get subtly wrong, so only keep it if reconciliation proves it equal |
| Money and shares | Integer minor units and fixed-point integers | Doubles or floating decimals: rounding drift breaks the hash-equality demo |
| Rounding | One rule, applied only when reporting | Rounding at every step: compounding error and order dependence |
| Stream processing | Plain consumer first | Flink from day one: heavy to run and debug in a 12-day window. Mention it as the scale-up path. |
| Reconciliation | Full replay into a scratch schema, then diff | Spot checks: miss drift that only appears in rare sequences |
| Superficial-loss model | Simplified, documented, flagged edge cases | Claiming full CRA compliance: not defensible and not the point |

Pick two of these and measure them. For example, compare incremental update against recompute-from-date on the same workload and publish the numbers.

## Testing and chaos strategy

Testing is the main evidence that this is not a toy. Show the test names in the README.

1. **Property-based tests** for the eight invariants. Generate random activity streams (random order, duplicates, reversals) and assert balance, idempotency, reversal symmetry, order independence and replay determinism.
2. **Golden tests** for ACB and superficial loss. Take worked examples from published Canadian ACB guides and the CRA's own explanations, encode them as fixtures with the source cited, and assert exact results. Do not invent expected values.
3. **Differential test.** Run your engine on the same inputs as an open-source calculator (for example tsiemens/acb, which is MIT-licensed) on the cases both support and report where and why results differ. This is a strong credibility signal.
4. **Integration tests** with Testcontainers: real Postgres and Redpanda, end to end from CSV to materialized pool.
5. **Chaos scenarios**, each scripted and repeatable:
   - Kill the consumer mid-batch, restart, and assert zero drift against replay.
   - Inject network delay and resets between the consumer and Postgres with Toxiproxy.
   - Redeliver an entire topic partition and assert nothing changes.
   - Post a late correction dated months earlier and assert the pool recomputes correctly.
   - Run two consumers briefly against the same partition (a rebalance) and assert no double-apply.
6. **Reconciliation as a test.** After every chaos scenario, run the replay diff and fail the build if drift is not zero.

Report test counts and the number of generated cases, not just "tests pass".

## Day-by-day plan

Ten build days and two buffer days. Each day ends with a commit and one "done when" check. If you only have one week, do days 1 to 5 and the demo; that still stands as a ledger project.

| Day | Work | Done when |
| --- | --- | --- |
| 1 | Repo, docker-compose (Postgres, Redpanda), CI skeleton, domain glossary, first three ADRs | `docker compose up` works; CI is green on an empty test |
| 2 | Canonical activity model, CSV parser, synthetic data generator (seeded, deterministic), golden fixtures | Parser round-trips your own export; generator makes the same file for the same seed |
| 3 | Ledger schema and posting: double-entry, integer money, idempotency key plus payload hash, reversals | Balance, idempotency and immutability property tests pass |
| 4 | Outbox and Kafka publishing; ingestion API | Duplicate submit is a no-op; payload mismatch returns a clear error |
| 5 | Consumer with transactional inbox; basic pool: buy, sell, ROC, fees | Replaying twice gives identical hashes; first chaos test (kill and restart) passes |
| 6 | Superficial-loss detection across accounts, including registered repurchases | Golden tests from published examples pass, each with its source cited |
| 7 | FX: Bank of Canada rate cache, trade-date conversion, prior-business-day rule for weekends and holidays | USD trades convert correctly on weekend and holiday fixtures |
| 8 | Late corrections and recompute-from-date; reconciliation job (replay diff) | Late correction test passes; reconciliation reports zero drift |
| 9 | Chaos suite, differential test against an open-source calculator, benchmark harness | All chaos scenarios scripted; benchmark report generated |
| 10 | React UI: book value vs true ACB with reasons; Grafana dashboard for lag and drift | Every difference on screen has a plain-language explanation |
| 11 | Buffer: fix flakiness, tighten README, write remaining ADRs, record demo | Fresh clone runs the demo end to end on a second machine or clean VM |
| 12 | Final polish and launch day (see launch plan) | Video, repo, posts all ready before you press publish |

Time-box each day. If a day slips, cut from the list in the risks section rather than skipping tests.

## Benchmarks and the 3-minute demo

**Measure, then publish only what you measured.** Record hardware, versions and seed so anyone can reproduce it. Suggested measurements:

- Ingest throughput (activities per second) and p50, p95 and p99 write latency.
- A "market-open burst": for example, a large batch of trades across many taxpayers injected in under a minute. Report consumer lag over time and time to drain.
- Replay time for a full year of synthetic activity, and recompute time after a late correction by how far back it reaches.
- Incremental update versus recompute-from-date, on the same workload.

Set yourself targets before you run them, then report actuals even if they miss. A missed target with a clear explanation reads better than an inflated number.

**Demo script (3 minutes, with a 60-second cut for social):**

1. **0:00 Hook.** One sentence: "Your broker's book value isn't your tax cost basis. Here's a ledger that proves the difference."
2. **0:15 Load.** Start the stack, load a seeded year of activity, show the book-value-versus-ACB page with differences explained.
3. **0:45 Determinism.** Replay twice, show identical hashes side by side.
4. **1:05 Duplicate.** Send the same activity again; show it ignored. Send the same key with a changed amount; show the rejection.
5. **1:25 Late correction.** Post a return-of-capital correction dated months back; watch the affected holding recompute, and show the reason text.
6. **1:55 Crash.** Kill the consumer mid-burst, restart, run reconciliation, show zero drift.
7. **2:25 Burst.** Run the market-open burst; show lag climbing and draining on the Grafana panel, with your measured numbers on screen.
8. **2:50 Close.** Repo link and one line on what you would build next.

Record the screen at 1080p with a clean terminal, large fonts and no secrets visible. Add captions, since most social video plays muted.

## Repo layout and README checklist

```
ledgerline/
  README.md            # hook, GIF, quickstart, architecture, results
  docs/adr/            # one decision record per decision above
  docs/architecture.md # the diagram plus a data-flow walkthrough
  docs/benchmarks.md   # method, hardware, results, raw data
  docs/tax-model.md    # simplified rules, sources, what is NOT modeled
  ledger/              # posting, idempotency, outbox
  calc/                # pools, superficial loss, FX, reconciliation
  ingest/              # CSV parsers, synthetic generator
  ui/                  # React app
  chaos/               # scripted failure scenarios
  bench/               # load generator and report scripts
  docker-compose.yml
```

**README must contain, in this order:**

1. One-sentence description and a 10-second GIF of the replay-hash demo.
2. Quickstart: three commands from a fresh clone to a running demo.
3. What it proves: the five demo claims, each linking to the test that backs it.
4. Architecture diagram and a short data-flow explanation.
5. Results table from your benchmarks, with hardware and seed.
6. Decisions and rejected alternatives, linking to the ADRs.
7. Limits and disclaimers: synthetic data, simplified tax model, not tax or financial advice, no affiliation with any company mentioned.
8. Credits: link the public engineering writing that inspired the design.

Before posting, clone the repo on a clean machine or VM and follow the README exactly. A broken quickstart is the fastest way to lose a skeptical engineer.

## Launch plan for X and LinkedIn

Tag companies only after the project can stand on its own. Tagging three large companies on a half-finished demo costs more credibility than it earns.

**Tagging etiquette:**

- **Be accurate about the relationship.** Say it is an independent project inspired by public engineering writing. Never imply it was built with, endorsed by, or reflects the internals of any of them.
- **Credit before you tag.** Link the Wealthsimple engineering post that inspired the design. Say what you borrowed (the architecture pattern) and what is yours.
- **Give each tag a reason.** One line per company explaining why it is relevant, not a bare list of handles.
- **Check handles right before posting.** Confirm the official accounts for Wealthsimple, Stripe and Robinhood yourself; don't rely on memory.
- **Skip logos and brand assets** in the video and thumbnail. Plain text names are fine.
- **Expect no reply.** Write the post so it is valuable without one.

**X thread outline (7 posts):**

1. Hook plus the 60-second video: the claim, one visual, repo link in the reply to keep reach.
2. The problem: broker book value is not your tax cost basis, and why the difference matters.
3. The architecture in one image: funnel, ledger, stream, calculation, reconciliation.
4. The proof: replay hashes match, duplicates are ignored, a crash causes zero drift.
5. The numbers: your measured throughput and burst result, with hardware noted.
6. The decision you are proudest of, with the alternative you rejected and why.
7. Credit and tags: link the inspiring engineering post, state the limits, then a line each for the companies, and repo link.

**LinkedIn post outline (one post, about 200 words):**

- First two lines: the hook and the result (they show before "see more").
- Three short paragraphs: the problem, what you built, what you learned.
- One decision with its trade-off.
- Repo and video link, limits and disclaimer, credit to the inspiring post, then tags.

**Timing and follow-through:**

- Post Tuesday to Thursday morning Eastern; reply to every comment in the first two hours.
- Pin the repo. Prepare answers to the likely questions: "why not Flink?", "is this tax advice?", "how do you handle partial fills?", "why Postgres?".
- Email or message the one or two engineers whose public writing you credited only if you can add something specific. Do not cold-spam recruiters.
- Keep the repo open to issues so you can show how you respond.

## Risks and cut list

| Risk | Likelihood | Mitigation |
| --- | --- | --- |
| Tax-rule edge cases consume the schedule | High | Freeze the simplified model on day 6; list what is not modeled; test only against cited worked examples |
| Kafka and consumer correctness bugs surface late | Medium | Get the kill-and-restart test passing on day 5, before adding features |
| FX holiday and weekend handling is wrong | Medium | Fixtures for weekends, long weekends and the first trading day after |
| Benchmark numbers disappoint | Medium | Report them honestly with the bottleneck explained; a profiled bottleneck is itself a good story |
| Demo breaks on a different machine | Medium | Clean-VM run on day 11; pin versions; cache FX rates |
| Someone reads it as tax advice or as company-affiliated | Low but costly | Prominent disclaimers in the README, the video and every post |

**Cut in this order if you run behind:**

1. Grafana dashboard (keep a simple lag and drift printout).
2. Differential test against the open-source calculator (keep the golden tests).
3. Foreign exchange beyond USD and CAD (already out of scope).
4. React UI polish (keep a plain table with reasons).
5. Late-correction recompute optimization (keep correctness, drop the incremental-versus-recompute comparison).

**Never cut:** the property tests, replay-hash determinism, the crash-and-reconcile demo, and the decision records. Those are the project.

**Stretch, only if you finish early:** a Flink version of the calculator for comparison, an options-activity type, or a tax-loss-harvesting candidate view reading from the same pools.

## Sources

From the research report for this project; most pages were summarized by the research tool, so re-open them before quoting.

- [Wealthsimple Engineering: Inside the Book of Record](https://engineering.wealthsimple.com/inside-the-book-of-record)
- [Wealthsimple Engineering: when two fintechs swap engineers](https://engineering.wealthsimple.com/what-happens-when-two-fintechs-swap-engineers)
- [Wealthsimple help: finding your adjusted cost base](https://help.wealthsimple.com/hc/en-ca/articles/360058455973-Finding-your-Adjusted-Cost-Base-information)
- [ActiveACB: book cost vs ACB](https://activeacb.ca/book-cost-vs-acb.html)
- [mycostbase: broker book value vs ACB](https://mycostbase.ca/blog/broker-book-value-vs-adjusted-cost-base-canada/)
- [Wealthsimple Streaming Platform job posting](https://careers.portageinvest.com/companies/wealthsimple/jobs/45174191-senior-software-developer-streaming-platform)
- [Wealthsimple Winter 2027 internship posting](https://careers.portageinvest.com/companies/wealthsimple/jobs/91817168-software-development-and-data-science-internships-winter-2027)
- [Statistics Canada Web Data Service](https://statcan.gc.ca/eng/developers/wds)
- [tsiemens/acb (open-source ACB calculator)](https://github.com/tsiemens/acb)
- [Wealthsimple Penny and 11 more releases](https://newsroom.wealthsimple.com/wealthsimple-announces-ai-assistant-penny-and-11-more-releases-at-wealthsimple-presents-something-good-is-going-to-happen)

Not yet verified by me: Stripe and Robinhood engineering practices described in the pitch are general industry knowledge, not from the research report. Check their public engineering blogs before you make specific claims about them.
