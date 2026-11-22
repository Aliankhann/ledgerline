# L-15 Differential ACB check against tsiemens/acb

**Status:** Draft  <!-- Draft → Approved → Tests written → In dev → In QE → Done. Only the owner sets Approved. -->
**Day:** — · **Branch:** `main` (owner rule) · **Round:** 0 / 3

## Story

As the owner, I want Ledgerline's ACB, capital gains and superficial-loss results checked against an independent
open-source calculator (tsiemens/acb) on many seeded synthetic histories, so that a reviewer can trust the numbers came
from more than our own reasoning.

## Why it matters

Every tax value in Ledgerline otherwise rests on two secondary websites (see `docs/status.md`, owner decisions). This is
a second, independent check. Closest evidence rows in [`docs/problem/evidence.md`](../problem/evidence.md): E-11
(existing tools tell users to double-check results themselves), E-02 (acb applies the superficial-loss rule) and E-01
(acb is an open-source Canadian ACB tool). No new evidence row fits, so none was added. Evidence is thin on whether
reviewers value this; that is a hypothesis, not a finding.

## Independent oracle

- Tool: tsiemens/acb, https://github.com/tsiemens/acb, MIT-licensed Rust CLI. Run as `acb <file.csv>`; install with
  `cargo install --git https://github.com/tsiemens/acb.git`.
- Facts below come from its README (read 2026-11-22): input header
  `security,trade date,settlement date,action,shares,amount/share,currency,exchange rate,commission,memo`; actions
  used here are Buy and Sell; it applies the superficial-loss rule automatically and emits rows with action `SfLA`
  ("automatic SfL ACB adjustment").
- **Version is not yet pinned.** Dev or QE must record the exact commit hash and `acb` version in the test fixture and in
  CI's install step. Expected values are whatever that pinned build prints, never agent reasoning (CLAUDE.md rule).
- Not yet verified by the BA: acb's output format (table or CSV, and which flag). QE must record how it is parsed.

## Scope

CAD only; one taxpayer; one account; Buy and Sell with commissions; histories include losses with repurchases inside
30 days. Rule `DATE-BASIS` (settlement date, `docs/tax-model.md`) applies. The exporter writes the tax date in both the
`trade date` and `settlement date` columns, so the two tools cannot differ on date basis.

## Acceptance criteria

1. **AC1: exporter** Given Ledgerline activities, when exported, then the CSV is accepted by `acb`.
   - Rules: header as above; one row per BUY or SELL in canonical order; `trade date` and `settlement date` both
     carry the activity's tax date; action `Buy` or `Sell`; shares as plain decimal without trailing zeros;
     `amount/share` = `amount / quantity` with exactly 2 decimals, and the exporter **rejects** an activity whose unit
     price is not an exact number of cents (the generator only emits such prices); `commission` with 2 decimals;
     `currency` `CAD`; `exchange rate` empty; `memo` = idempotency key.
   - Input: `TP-0001-2025-0002,TP-0001,TP-0001-NR1,NON_REGISTERED,BUY,2025-01-07,2025-01-08,SYN-AAA,100,CAD,3000.00,4.95,,,`
     and `TP-0001-2025-0003,TP-0001,TP-0001-NR1,NON_REGISTERED,SELL,2025-02-10,2025-02-11,SYN-AAA,40,CAD,1400.00,4.95,,,`
   - Expected (exact text, `\n` line endings, trailing newline):
     ```
     security,trade date,settlement date,action,shares,amount/share,currency,exchange rate,commission,memo
     SYN-AAA,2025-01-08,2025-01-08,Buy,100,30.00,CAD,,4.95,TP-0001-2025-0002
     SYN-AAA,2025-02-11,2025-02-11,Sell,40,35.00,CAD,,4.95,TP-0001-2025-0003
     ```
   - Source: format from the acb README; no tax value in this criterion.
2. **AC2: final position** Given seeds 1..200 with fixed generator settings (recorded in the test), when each history
   is run through Ledgerline and `acb`, then per security the final share count equals acb's, and the final ACB equals
   acb's to the cent, comparing our pool ACB after the documented reporting rounding (`ROUND-SCALE`, `ROUND-MODE`,
   `ROUND-BOUNDARY`).
   - Expected: 200 of 200 histories match. Values come from acb's output.
3. **AC3: gain or loss per sell** Every sell's capital gain or loss equals acb's to the cent, matched by security and
   tax date (and by memo, the idempotency key, where acb preserves it; QE to confirm).
   - Expected: 0 differences across all 200 histories.
4. **AC4: superficial-loss denial** For every loss denied by acb, Ledgerline denies the same amount and adds the same
   amount to the pool ACB (acb's `SfLA` row). Ledgerline denies nothing acb does not.
   - Expected: denied amount and ACB added match to the cent; the set of sells with a denial is identical.
   - Generator must produce at least 20 of the 200 histories with a loss followed or preceded by a repurchase within
     30 days; the test fails if fewer are generated (otherwise AC4 passes vacuously).
5. **AC5: a mismatch never changes a rule** Given any difference, then the test fails with a message naming the seed,
   the security, the field, Ledgerline's value and acb's value. Nobody edits a tax rule, fixture or expected value to
   make it pass. The difference is filed under `docs/bugs/`, then listed in `docs/status.md` Open questions for the
   owner.
   - Input: a deliberately altered Ledgerline result for seed 7, security `SYN-AAA` (test-only fault injection).
   - Expected: failure message of the form `seed=7 security=SYN-AAA field=<name> ledgerline=<v> acb=<v>`.
6. **AC6: tagging and skip** The test carries `dev.ledgerline.testsupport.Integration`. When the `acb` binary is not on
   `PATH`, it is skipped with the message `acb not found on PATH: install with cargo install --git
   https://github.com/tsiemens/acb.git`. CI installs the pinned build, so there it runs.
   - Input: run with `PATH` lacking `acb`. Expected: reported as skipped with that message, not as passed.

## Out of scope

- USD and FX; return of capital; multiple accounts or affiliated persons; registered accounts; stock splits.
- Partial superficial loss where the two tools document different formulas (see below).
- Changing a tax rule because acb disagrees (AC5).
- Running acb against real or personal data. Synthetic seeded histories only.

## Needs owner decision

- **Known possible difference: partial superficial loss (`SL-PARTIAL`).** Ledgerline's default prorates by
  least(S, P, B)/S (TAXTIPS, second-hand, CRA T.I. 2004-0073011E5 not opened). The BA has not read acb's source or
  documented formula for the partial case. If the generator produces partial repurchases and the tools differ, that is
  an owner decision on the tax rule, not a test failure to patch. Choice: (a) restrict the 200 histories to
  repurchases of at least the sold quantity until the owner decides, or (b) include partials and treat each difference
  as a filed bug and Open question. Proposed default: (a), with a separate partial-case run reported, not gating.
- **Tax judgment:** any mismatch is a tax-judgment question for the owner (AC5). A match proves agreement with one
  open-source tool whose author disclaims warranty (E-11), not correctness under the Income Tax Act. State this in any
  launch copy.
- Whether CI should block on a mismatch or only report it (proposed: block, since AC5 forbids silent edits).

## Test map (QE fills)

| AC | Test |
| --- | --- |
| AC1 | `tests/spec/.../L15Spec.kt` › "L-15 AC1: …" |

## Change notes (Dev fills)

- …
