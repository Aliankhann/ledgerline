# 0002 Money and shares as integers

**Status:** Draft · **Date:** 2026-11-19 · **Drafted by:** agent (from `docs/build-plan.md`)

## Context

The demo's strongest claim is byte-identical replay hashes (invariant 5). Any floating-point arithmetic risks
order-dependent rounding drift. Invariant 8 bans floating point.

## Options considered

1. **`Double`**: fast, but inexact, and rounding depends on operation order.
2. **`BigDecimal`**: exact, but scale and rounding mode must be managed at every operation, and equal values can
   compare unequal when the scale differs.
3. **Integers**: cash in `Long` minor units; shares as fixed-point `Long` (scale to be chosen, for example 10⁻⁶ share);
   pool ACB in a finer unit (for example millionths of a cent); one rounding rule applied only at reporting.

## Decision

_Owner, in your own words. Choose the share scale, the ACB unit and the single rounding rule (for example half-even)._

## Rejected alternatives and why

- Doubles: rounding drift breaks hash equality.
- Floating decimals with per-step rounding: compounding error and order dependence.

## Consequences

- Overflow becomes the risk instead of rounding: check headroom of `Long` at the chosen scales against the largest
  synthetic portfolio, and use `Math.multiplyExact` and similar so overflow fails loudly.
- Verified by the money-precision property test and replay-hash equality.
