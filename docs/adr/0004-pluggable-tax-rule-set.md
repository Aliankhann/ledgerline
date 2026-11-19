# 0004 Tax rules as a versioned, pluggable rule set

**Status:** Draft · **Date:** 2026-11-19 · **Drafted by:** agent (owner asked for a plug-and-play tax schema)

## Context

The tax stories (L-06, L-07, L-08) leave about 15 questions open: trade or settlement date, partial-repurchase
proration, rounding, return-of-capital timing, FX fallback, and so on. The owner wants defaults that stay as close to
Canadian law as possible (`docs/tax-model.md`), and a design where the rules can change later without rewriting the
engine or breaking replay determinism (invariant 5).

## Options considered

1. **Hard-code the rules** in the ACB calculator, with constants and `if` branches.
2. **A configuration file of flags** read at start-up.
3. **A versioned `TaxRuleSet`**: an interface in `calc/` whose implementations answer each rule question, identified
   by an ID and version (for example `CA-ITA-2026.1`). The ACB plugin receives a rule set; it never decides tax
   policy itself.

## Decision

_Owner, in your own words._

## Proposed shape (for Dev to refine)

- `TaxRuleSet { id; version; dispositionDate(activity); superficialLossWindowDays; partialDenial(S, P, B, loss);
  registeredRepurchase(); negativeAcb(); fxRateDate(activity); fxFallback(); rounding() ... }`. There is one method
  per rule ID in `docs/tax-model.md`.
- The rule set ID and version are written into every pool snapshot and **into the replay hash**, so the same ledger
  under two rule sets gives two different, reproducible hashes, and reconciliation never compares across rule sets.
- Every explanation ("reason") a pool produces names the rule ID that applied, so the UI can show *why*.
- Rule sets are pure functions: no I/O, no clock, no floating point. That keeps them testable with property tests.
- A taxpayer is calculated under exactly one rule set per run. Choosing per taxpayer is a later option.

## Rejected alternatives and why

- Hard-coding makes every future rule change a code change in the engine, and replay output silently changes meaning.
- Flags in a config file can be combined into states nobody tested, and the hash wouldn't record which flags were on.

## Consequences

- QE writes the golden tests against `CA-ITA-2026.1`. Each test names the rule set it assumes.
- A second rule set (for example "broker book value" rules, to reproduce what a broker shows) becomes cheap. It could
  also power the L-11 book value vs ACB explanation: run both rule sets and explain the differences rule by rule.
- Costs: one more interface, and rule-set versioning discipline (never edit a released version; add a new one).
