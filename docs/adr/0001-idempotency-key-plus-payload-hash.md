# 0001 Idempotency: unique key plus payload hash

**Status:** Draft · **Date:** 2026-11-19 · **Drafted by:** agent (from `docs/build-plan.md`)

## Context

Every activity enters through one funnel. Clients retry, CSVs get uploaded twice, and the outbox and consumer deliver at
least once. Invariant 2 says submitting the same activity twice changes nothing, and the same key with a different
payload is rejected. Story L-02 tests this.

## Options considered

1. **Unique key only**: a repeat key is treated as a duplicate and ignored.
2. **Unique key plus a hash of the canonical payload, stored beside it**: a repeat key with an equal hash is a no-op;
   a repeat key with a different hash is rejected with a clear error.
3. **Content hash as the key** (no client key): two genuinely identical trades on the same day would collapse into one.

## Decision

_Owner, in your own words._

## Rejected alternatives and why

- Key only lets a changed payload under the same key slip through silently, which corrupts the ledger with no trace.
- Content hash as key can't tell a duplicate upload from two real identical trades.

## Consequences

- The payload must have one canonical serialization (field order, number format) so the hash is stable on any machine.
- Open questions: which fields are in the hash (is `recorded_at` excluded?); how CSV rows derive a key when the export
  has no id column.
- Verified by the L-02 spec tests and the idempotency property test.
