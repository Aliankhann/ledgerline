# chaos/

Scripts that drive failure scenarios against the running stack (`docker compose --profile chaos up`).
The assertions live in `tests/chaos/` (QE-owned). Planned scenarios, from `docs/build-plan.md`:

1. Kill the consumer mid-batch, restart, assert zero drift against replay.
2. Toxiproxy delay and resets between the consumer and Postgres.
3. Redeliver a whole topic partition; assert nothing changes.
4. Late correction dated months earlier; assert the pool recomputes.
5. Two consumers on one partition (rebalance); assert no double-apply.
