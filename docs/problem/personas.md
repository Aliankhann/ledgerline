# Personas

_BA-owned. Two or three lines each. Step 0.2 fills this file._

Each persona cites the evidence log (`docs/problem/evidence.md`). E-IDs are sourced; A-IDs are **assumptions** awaiting
a source or the owner's step 0.3 conversations. No quotes here come from real users.

## Self-directed investor

A Canadian who files their own taxes and holds stocks, ETFs, RSUs or ESPP shares, often at more than one broker
(Questrade, RBC DI, E*TRADE, Wealthsimple) alongside a TFSA or RRSP (E-01, E-03, E-05, E-06, E-14, E-30). They have to
handle return of capital, USD trades at Bank of Canada rates and the superficial-loss rule, and they check figures to
the cent (E-02, E-04, E-07, E-08, E-25, E-26, E-29). Existing tools tell them to double-check the results themselves
(E-11), and their broker's book value can differ from their ACB for reasons the broker cannot see (E-21 to E-24,
E-28; vendor sources, Wealthsimple and CRA wording second-hand).
_Assumed, not sourced:_ they want a plain-language reason for each difference (A-11; E-31 is vendor advice, not user
demand). Audience size and the investors' own words are unsourced.

## Reviewing engineer

An engineer at a fintech (brokerage, payments or wealth platform) who skims a candidate's repo to judge whether they
understand ledgers, idempotency, streaming and reconciliation. They trust tests, replay hashes and measured numbers more
than claims. **This persona is still mostly assumption** (A-08, A-09). Wealthsimple publicly describes a Book of Record
built from a validated funnel, an append-only double-entry ledger and a separate streaming read path (E-16 to E-19),
and its ledger team talks about load testing and legibility (E-20, weak). No page says what reviewers look for in a
candidate or which stack they want, so the owner's conversations with engineers in step 0.3 remain the main way to
confirm or revise this persona.
