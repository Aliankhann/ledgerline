-- V4 (story L-02): idempotent ingestion.
--
-- activity : one row per accepted submission, written in the same transaction as its entry. Holds the payload hash
--            (SHA-256 hex, OWNER-INPUT: C20) beside the unique key, the canonical activity fields, and recorded_at
--            (the first accepted submission's time). A repeat of the key is compared with payload_hash.
-- outbox   : one row per newly posted entry, written in the same transaction. Append-only, so it has no "sent"
--            marker; delivery state is L-05's (a separate table, OWNER-INPUT: C5). L-05 extends this table.
--
-- Both tables follow V2: ledgerline_app holds SELECT and INSERT only, and a statement trigger refuses UPDATE, DELETE
-- and TRUNCATE for every role. The unique constraints are what make concurrent duplicates safe (23505 is handled in
-- LedgerRepository.submit).
--
-- No foreign keys on purpose: Postgres checks a foreign key before the append-only trigger fires, so a table that
-- references ledger.entry would turn "TRUNCATE ledger.entry, ledger.line" into an FK error instead of "append-only"
-- (the L-03 superuser edge test pins that message). The rows are written in one transaction with their entry, and the
-- UNIQUE columns pair them one to one.
--
-- NOTE for merging: if another branch (for example L-04) also adds a V4 migration, one of them must be renumbered
-- before either is applied anywhere.

CREATE TABLE ledger.activity (
    activity_id       text        PRIMARY KEY CHECK (activity_id <> ''),
    entry_id          bigint      NOT NULL UNIQUE,
    idempotency_key   text        NOT NULL UNIQUE,
    payload_hash      text        NOT NULL CHECK (payload_hash ~ '^[0-9a-f]{64}$'),
    recorded_at       timestamptz NOT NULL,
    taxpayer_id       text        NOT NULL,
    account_id        text        NOT NULL,
    account_type      text        NOT NULL,
    activity_type     text        NOT NULL,
    effective_date    date        NOT NULL,
    settlement_date   date,
    security_id       text,
    quantity          bigint,
    currency          text        NOT NULL,
    amount            bigint      NOT NULL,
    commission        bigint      NOT NULL,
    counter_currency  text,
    counter_amount    bigint,
    description       text,
    source_seq        bigint      NOT NULL
);

CREATE TABLE ledger.outbox (
    outbox_id   bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    entry_id    bigint NOT NULL UNIQUE,
    activity_id text   NOT NULL UNIQUE
);

CREATE TRIGGER activity_append_only
    BEFORE UPDATE OR DELETE OR TRUNCATE ON ledger.activity
    FOR EACH STATEMENT EXECUTE FUNCTION ledger.reject_mutation();

CREATE TRIGGER outbox_append_only
    BEFORE UPDATE OR DELETE OR TRUNCATE ON ledger.outbox
    FOR EACH STATEMENT EXECUTE FUNCTION ledger.reject_mutation();

REVOKE ALL ON ledger.activity, ledger.outbox FROM PUBLIC;
GRANT SELECT, INSERT ON ledger.activity, ledger.outbox TO ledgerline_app;
-- V2 granted USAGE on the sequences that existed then; outbox_id's sequence is new.
GRANT USAGE ON ALL SEQUENCES IN SCHEMA ledger TO ledgerline_app;
REVOKE UPDATE, DELETE, TRUNCATE ON ledger.activity, ledger.outbox FROM ledgerline_app;
