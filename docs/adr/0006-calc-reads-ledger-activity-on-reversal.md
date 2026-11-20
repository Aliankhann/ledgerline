# 0006 Calc recomputes a pool from ledger.activity on a reversal

**Status:** Draft · **Date:** 2026-11-20 · **Drafted by:** agent (dev, story L-12, bug B-024)

## Context

`ENTRY_REVERSED` carries only `reversesEntryId`. The pool consumer keeps totals, not history, so it cannot undo the
reversed activity from its own state. C19 replay skips a reversed pair, and the live pool must match it.

## Decision (built, for the owner to confirm or rewrite)

On a reversal the consumer reads `ledger.activity` and `ledger.reversal` (same database, SELECT already granted to the
app role) for the affected (taxpayer, security), keeps activities with an entry id below the reversal's that are not
reversed, runs `AcbEngine.replay`, and writes the resulting pool (or deletes the row if none) in the same transaction
as the inbox row and offset. A replay rejection fails the transaction; nothing is clipped.

## Consequences

- `calc/` now reads the ledger's tables, not only events. A separate calc database would need the activities carried
  in the event, or a history table of its own.
- Cost grows with the pool's history per reversal. `PoolRecomputer` (L-09) would limit that once pools keep history.
- Relies on per-taxpayer event order (key is the taxpayer) to treat lower entry ids as already consumed.
- Needs `DELETE` on `calc.pool` (migration V2).
