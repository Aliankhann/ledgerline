# Architecture

Draft describing what is built today. Items marked (follow-up) are not wired end to end yet.

## Data flow

```
CSV / generator -> ingest -> funnel (validate, idempotency key + payload hash)
                               | one Postgres transaction
                               v
                   ledger (append-only, double-entry) + outbox
                               | outbox drainer (at-least-once; unpublishable events parked)
                               v
                   Redpanda topic (key = taxpayer id)
                               v
                   inbox consumer (inbox row + pool state + offset in one transaction)
                               v
                   pools (calc.pool) --> book-value vs true-ACB view (API)
                               ^
          reconciler: replay all posted activities, diff against live pools; drift must be empty
```

1. **Funnel** validates an activity and checks its key and payload hash. A repeat with the same payload is a no-op; the
   same key with another payload is rejected.
2. **Ledger** posts balanced entries and lines plus an outbox row in one transaction. Rows are never updated or
   deleted; a correction is a reversal plus a new activity.
3. **Outbox** drainer publishes to Redpanda and records delivery.
4. **Inbox consumer** dedupes by event id and applies it to the pools, committing state and offset together, so a crash
   replays at most the uncommitted batch.
5. **Pools** hold ACB per taxpayer and security. Calculation order is effective date, then sequence.
6. **Reconciler** replays the posted activities and compares pools and hashes with the live state.
7. **Book-value view** compares book value to true ACB.

(follow-up) wiring the consumer into the app and compose stack; a UI table for the book-value view.

## Postgres schemas

Created in `ledger` migration V1; tables arrive with their features.

| Schema | Tables | Notes |
| --- | --- | --- |
| `ledger` | `entry`, `line`, `activity`, `outbox`, `reversal`, `outbox_delivery`, `outbox_parked` | Source of truth. Migrations V1 to V8 in `ledger/src/main/resources/db/migration/`. |
| `calc` | `inbox`, `pool`, `consumer_offset` | Derived and rebuildable. Migration in `calc/src/main/resources/db/calc/`. |
| `recon` | none yet | Schema reserved for replay scratch output; the current reconciler is in memory. |
