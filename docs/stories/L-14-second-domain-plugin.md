# L-14 Second domain plugin

**Status:** Draft  <!-- Draft → Approved → Tests written → In dev → In QE → Done. Only the owner sets Approved. -->
**Day:** 10 · **Branch:** `L-14-second-domain-plugin` · **Round:** 0 / 3

## Story

As a reviewing engineer, I want a second, small plugin to run on the same ledger, funnel, stream and reconciliation
without changing the core, so that I can see the core is domain-neutral and reusable, not hard-wired to ACB.

## Why it matters

The pitch is the engineering, and a reusable core is what makes the pattern relevant beyond Canadian ACB. See
[`docs/problem/evidence.md`](../problem/evidence.md): E-20 (weak; a Wealthsimple engineer says fintechs "are largely
solving the same set of hard problems: ledgers, payments"). That Stripe- or Robinhood-style systems share this pattern
is not sourced (A-09).

## Notes for QE and Dev

- Uses the `DomainPlugin` interface defined on day 3 (L-03 AC10): validate an activity, apply it to state, explain a
  result.
- **Proposed plugin: cash-balance tracker** (Stripe-style): per account and currency, the running cash balance from
  deposits, withdrawals, fees, buys, sells, dividends, returns of capital and FX conversions. It is pure integer
  arithmetic, with no tax logic. The alternative from the build plan (a position and average-price tracker,
  Robinhood-style) is an owner decision; if chosen, AC3 to AC5 are rewritten.
- On the cut list: drop this story first if the schedule slips (build plan).
- Fixture F2 for `TP-0001-NR1`, all `NON_REGISTERED`:

  | Key | Type | Effective date | Currency | Amount (minor) | Commission | Other |
  | --- | --- | --- | --- | --- | --- | --- |
  | `TP-0001-2025-0001` | DEPOSIT | 2025-01-06 | CAD | 1000000 | 0 | |
  | `TP-0001-2025-0002` | BUY | 2025-01-07 | CAD | 301250 | 495 | `SYN-AAA`, 100 shares |
  | `TP-0001-2025-0003` | FX_CONVERSION | 2025-01-09 | CAD | 138000 | 0 | counter `USD` 100000 |
  | `TP-0001-2025-0004` | DIVIDEND | 2025-03-31 | CAD | 1234 | 0 | `SYN-AAA` |
  | `TP-0001-2025-0005` | FEE | 2025-04-01 | CAD | 995 | 0 | |
  | `TP-0001-2025-0006` | BUY | 2025-04-02 | USD | 50000 | 495 | `SYN-USX`, 10 shares |

## Acceptance criteria

1. **AC1 The core is unchanged.**
   - Input: `git diff --name-only main...L-14-second-domain-plugin`.
   - Expected: no path under `core/` or `ledger/`. (Paths under `calc/` are allowed only for registering the plugin, if
     the owner places plugins there; see below.)
2. **AC2 The plugin passes the same contract tests as the ACB plugin.**
   - Input: the generic `DomainPlugin` contract suite in `tests/` (QE-owned), run against both plugins.
   - Expected: both pass: `apply` is deterministic, a redelivered event changes nothing, applying an activity and then
     its reversal returns the prior state, and replaying in canonical order twice gives the same state hash.
3. **AC3 Cash balances per account and currency.**
   - Input: F2 posted and consumed.
   - Expected: `TP-0001-NR1` CAD balance `1000000 − 301745 − 138000 + 1234 − 995 = 560494`; USD balance
     `100000 − 50495 = 49505`.
4. **AC4 Explain lists the contributing entries.**
   - Input: explain the CAD balance of `TP-0001-NR1` after AC3.
   - Expected: the five CAD-affecting activities (`-0001` to `-0005`) in effective-date order, each with its signed
     amount (`+1000000`, `−301745`, `−138000`, `+1234`, `−995`), and no USD-only activity.
5. **AC5 Overdraft is refused at validation (proposed default).**
   - Input: after AC3, `WITHDRAWAL` `TP-0001-2025-0007` of `560495` CAD dated `2025-05-01` (one cent more than held).
   - Expected: `validate` rejects with `INSUFFICIENT_CASH` naming account `TP-0001-NR1`, currency `CAD`, available
     `560494`; nothing is posted.
6. **AC6 Plugin balances agree with the ledger.**
   - Input: the seed `42` year loaded.
   - Expected: for every account and currency, the plugin's balance equals the ledger's cash-account balance for that
     account and currency.
7. **AC7 Reconciliation covers the plugin.**
   - Input: the seed `42` year loaded; run `reconcile` (L-10).
   - Expected: exit code `0`; the report lists the cash-balance plugin's state as compared, with zero drift; tampering
     one plugin balance by `1` makes it exit `1` with that account and currency named.

## Out of scope

- A third plugin; any tax logic in this plugin.
- Interest, withholding tax on dividends, and multi-currency totals.
- UI for the plugin (an API or CLI printout is enough).

## Needs owner decision

- **Which second plugin**: cash-balance tracker (proposed, no rounding involved) or position and average-price tracker
  (needs a division rounding rule, which ADR 0002 would have to cover).
- **Where plugins live**: a new module (for example `plugins/cash/`) or inside `calc/`. The repo layout in `CLAUDE.md`
  has no plugin module yet; a new module changes the layout.
- **Overdraft policy** (AC5): refuse at validation (proposed) or allow negative balances and flag them. If cash is
  checked at validation, the plugin's `validate` must run in the funnel, which is an architecture choice.
- **Is reconciliation plugin-generic?** AC7 assumes L-10 replays every registered plugin. If L-10 is built ACB-only,
  AC7 needs L-10 changes, which conflicts with AC1's spirit.

## Test map (QE fills)

| AC | Test |
| --- | --- |
| AC1 | `tests/spec/.../L14Spec.kt` › "L-14 AC1: …" |

## Change notes (Dev fills)

- …
