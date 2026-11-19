# Architecture

_Owner-owned. Diagram plus a data-flow walkthrough; filled in as stages are built._

```
CSV / generator ─▶ ingest ─▶ funnel (validate, idempotency key + payload hash)
                               │  one Postgres transaction
                               ▼
                     ledger (append-only, double-entry) + outbox
                               │  outbox drainer
                               ▼
                     Kafka topic ledgerline.activities.v1 (key = taxpayer id)
                               │  at-least-once
                               ▼
                     calc consumer (inbox + state + offset in one transaction)
                               ▼
                     ACB pools ─▶ API / UI
                               ▲
          reconciliation: full ledger replay into recon schema ─▶ diff must be zero
```

| Postgres schema | Holds |
| --- | --- |
| `ledger` | entries, lines, idempotency keys, outbox (source of truth) |
| `calc` | inbox, pools, adjustments (derived; rebuildable) |
| `recon` | scratch replay output for diffs |
