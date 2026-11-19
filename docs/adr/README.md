# Architecture decision records

One file per decision, `NNNN-short-title.md`, copied from [`0000-template.md`](0000-template.md). Agents may draft;
the owner writes the Decision section in their own words and sets the status to Accepted. Lead with the rejected
alternative when explaining a decision; that's the part that shows judgment.

| ADR | Title | Status |
| --- | --- | --- |
| [0001](0001-idempotency-key-plus-payload-hash.md) | Idempotency: unique key plus payload hash | Draft |
| [0002](0002-integer-money-and-fixed-point-shares.md) | Money and shares as integers | Draft |
| [0003](0003-corrections-by-reversal.md) | Corrections by reversal plus a new activity | Draft |

Planned (from `docs/build-plan.md`): transactional outbox, at-least-once plus idempotent consumer, partition key =
taxpayer id, effective-date ordering, recompute-from-date for late corrections, rounding only at reporting, plain
consumer before Flink, reconciliation by full replay, simplified superficial-loss model.
