# Launch hypotheses

_BA-owned. Every item is a hypothesis, not a promise._

Rules for this file:

- No targets or numbers are filled in by the BA. Wherever a threshold is needed, it reads **N = owner sets before
  launch**. Set every threshold and window **before** posting, write them here, and do not change them afterwards.
  Changing a threshold after seeing the results defeats the test.
- Record observed results below each hypothesis with the date and where you looked. Never round up or estimate.
- Evidence links point to `docs/problem/evidence.md`. Most of the audience claims there are still assumptions (A-IDs),
  so these hypotheses test the assumptions as much as the launch.

## Problem hypotheses (tested in owner conversations, step 0.3)

### H-01: Investors recognize the book-value gap (hypothesis)

- **Statement:** Canadian self-directed investors with holdings at more than one broker, or with RoC or USD holdings,
  have seen their broker's book value differ from what they believe their ACB is.
- **Tests:** A-01 to A-05, E-14.
- **Measure:** In the step 0.3 conversations, ask an open question ("How do you work out your cost base at tax time?")
  before mentioning book value. Count how many people bring up a mismatch unprompted, and write down the cause they give.
- **Falsified if:** none of the investors spoken to bring up a mismatch, or fewer than N do (N = owner sets before
  launch). If so, lead the public narrative with the engineering, not the investor problem.

### H-02: A per-difference reason is what investors lack (hypothesis)

- **Statement:** Investors who use an ACB tool want to know *why* their number differs from their broker's, not just
  a second number.
- **Tests:** A-11, E-11.
- **Measure:** In the step 0.3 conversations, show a mock row of "book value, ACB, reason". Record whether they ask
  for the reason or ignore it.
- **Falsified if:** most investors spoken to say the reason is not useful, or they would not trust it over their
  broker. If so, deprioritize the reason text in L-11.

## Launch and traction hypotheses (measured after posting)

### H-03: Working engineers reply on substance (hypothesis)

- **Statement:** Engineers who work on ledgers, payments or brokerages will reply to the launch posts with technical
  questions or critique (for example "why not Flink?" or "how do you handle partial fills?"), not just reactions.
- **Tests:** A-07, A-08, A-09.
- **Measure:** Count replies on X, LinkedIn and GitHub issues within a window W (W = owner sets) from people whose
  public profile shows they work as an engineer. Count only replies that mention a specific design choice. Keep a list
  of links, not just a count.
- **Falsified if:** no substantive replies from working engineers within W, or fewer than N (owner sets). If so, check
  whether the post reached engineers at all (impressions, where shown) before concluding the content failed.

### H-04: Stars and forks show engineers find the repo worth keeping (hypothesis)

- **Statement:** The repo earns stars and forks from people outside the owner's existing network.
- **Measure:** Star and fork counts on GitHub at fixed checkpoints (owner sets the dates, for example launch day and
  one fixed later date). Record the raw count and the date. For context only: tsiemens/acb had 35 stars and 11 forks
  on 2026-11-19 (E-13). That is a different audience and is not a target.
- **Falsified if:** at the final checkpoint, stars from accounts outside the owner's network are below N (owner sets).
- **Caveat:** stars are a weak signal of quality. Read them alongside H-03 and H-06 rather than on their own.

### H-05: The project leads to conversations (hypothesis)

- **Statement:** At least some reviewing engineers or hiring contacts will ask for a conversation (call, interview or
  DM thread) because of the project.
- **Measure:** Log each inbound request with the date, the channel and whether it mentions the project specifically.
  Count only requests that mention Ledgerline or something in it.
- **Falsified if:** no project-specific conversation requests within W (owner sets).

### H-06: Engineers run the quickstart and it works (hypothesis)

- **Statement:** People who see the post will clone the repo and run the quickstart, and it will work for them on the
  first try.
- **Measure (no telemetry; adding any would need an owner decision and an ADR):**
  1. Ask in the post and the README for a reply or a GitHub issue that says "ran it", with OS and outcome. Provide an
     issue template for both success and failure.
  2. Count GitHub issues labeled as quickstart failures.
  3. Optionally record GitHub's repository traffic (clones). _Assumption:_ GitHub shows repo owners clone counts for a
     recent window. Check this in the repo's Insights tab before relying on it. Clones are not the same as successful runs.
- **Falsified if:** the number of quickstart failures reported is at least the number of successes reported, or no
  one reports a run within W (owner sets). In either case, fix the quickstart before any further promotion.

### H-07: The verification story is the hook, not the calculator (hypothesis)

- **Statement:** Engineers respond more to the proof (replay hashes, zero drift after a crash, duplicate rejection)
  than to the ACB feature itself. This matches the build plan's claim that ACB calculators already exist (E-12).
- **Measure:** Tag each substantive reply from H-03 as "about verification or engineering" or "about tax or ACB".
  Report both counts.
- **Falsified if:** replies about tax or ACB outnumber replies about verification. If so, the investor angle carries
  more weight than assumed, so revisit H-01 and H-02.

## Results log

_Owner fills this in after launch: date, hypothesis ID, raw observation, link._
