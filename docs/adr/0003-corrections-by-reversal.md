# 0003 Corrections by reversal plus a new activity

**Status:** Draft · **Date:** 2026-11-19 · **Drafted by:** agent (from `docs/build-plan.md`)

## Context

Activities get corrected: a wrong amount, a late return-of-capital notice, a mis-entered date. The ledger is
append-only (invariant 3), and a reversal must return every balance to its prior value (invariant 4). Stories L-04 and
L-09 depend on this.

## Options considered

1. **Edit the row in place.**
2. **Soft-delete plus insert.**
3. **Reversal entry plus a new activity**: append a compensating entry that cancels the original line for line, then
   post the corrected activity as new.

## Decision

_Owner, in your own words._

## Rejected alternatives and why

- In-place edits destroy the audit history and break replay: yesterday's replay and today's would disagree with no
  record of why.
- Soft delete still mutates rows, and every reader must remember to filter.

## Consequences

- Pools affected by a back-dated correction must be recomputed from the earliest changed effective date (ADR planned).
- Reversing a reversal, and reversing an activity that doesn't exist, need defined behaviour (owner decision).
- Enforce immutability in the database itself (for example revoke `UPDATE` and `DELETE`, or a trigger), not only in code.
- Verified by the reversal-symmetry and immutability property tests.
