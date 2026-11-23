# 0007 A correction is one unit for the pool consumer

**Status:** Draft · **Date:** 2026-11-23 · **Drafted by:** agent (dev, bug B-029)

## Context

`LedgerRepository.correct` writes a reversal and a replacement in one transaction, and the ledger checks non-negative
shares on the pair. The consumer handles `ENTRY_REVERSED` alone (ADR 0006) and could not see the replacement, so a
later SELL looked newly rejected and the consumer stalled. The reversal event and the data carried no link to the
replacement. Entry-id adjacency is ambiguous, and transaction xmin is unreliable once Postgres freezes rows.

## Decision (built, for the owner to confirm or rewrite)

A new append-only table `ledger.correction (reversal_entry_id, replacement_entry_id)` (migration V9, same trigger and
grants as `ledger.reversal`) is written by `correct` in the same transaction. When the consumer applies an
`ENTRY_REVERSED` event that has a row there, the "newly rejected" check runs on the state after the whole correction
(entries up to the replacement). A reversal with no row is checked exactly as before (B-026). Each event is judged against reversals that existed at
that event only (B-030): a reversal row excludes an entry only if its reversal entry id is at most the event's own
entry (or the correction's replacement), so a later reversal of the replacement does not hide it. No event shape changes.

## Alternatives not taken

- Carry the replacement's entry id in the event: changes the event shape.
- Skip the check until the replacement event arrives, or skip it for corrections: weaker, and needs the same link.

## Consequences

- The check cannot hide a real negative-shares state: a link row exists only if the ledger accepted the pair, and a
  rejection that remains after the replacement still fails the transaction.
- Corrections posted before V9 have no row and are treated as standalone reversals.
