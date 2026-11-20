-- V1 (story L-12): the pool consumer's state, in schema calc. Applied by CalcMigrations after the ledger's migrations
-- (the role ledgerline_app is created by the ledger's V2), with its own Flyway history table.
--
-- Unlike ledger.*, this state is DERIVED: it can be rebuilt by replaying the ledger, so the pool and offset rows are
-- updated in place and the ledger's append-only trigger does not apply here. The inbox is still insert-only for the
-- app role: an event id once recorded is never removed.
--
-- The inbox row, the pool change and the offset are written in ONE transaction per consumed record (PoolConsumer).

CREATE SCHEMA IF NOT EXISTS calc;

CREATE TABLE calc.inbox (
    event_id   bigint PRIMARY KEY,
    applied_at timestamptz NOT NULL DEFAULT now()
);

-- Live pools. Shares in 10^-6 share units, total_acb in the engine's internal units: integers only.
CREATE TABLE calc.pool (
    taxpayer_id text   NOT NULL,
    security_id text   NOT NULL,
    shares      bigint NOT NULL CHECK (shares >= 0),
    total_acb   bigint NOT NULL CHECK (total_acb >= 0),
    PRIMARY KEY (taxpayer_id, security_id)
);

-- The consumer owns its offsets: next_offset is the next record to read, committed with the state it follows.
CREATE TABLE calc.consumer_offset (
    group_id    text   NOT NULL,
    topic       text   NOT NULL,
    partition_id int   NOT NULL,
    next_offset bigint NOT NULL CHECK (next_offset >= 0),
    PRIMARY KEY (group_id, topic, partition_id)
);

GRANT USAGE ON SCHEMA calc TO ledgerline_app;
GRANT SELECT, INSERT ON calc.inbox TO ledgerline_app;
GRANT SELECT, INSERT, UPDATE ON calc.pool, calc.consumer_offset TO ledgerline_app;
