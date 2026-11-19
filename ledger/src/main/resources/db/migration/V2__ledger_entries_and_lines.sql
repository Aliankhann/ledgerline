-- V2 (story L-03): the append-only double-entry ledger.
--
-- entry : one row per posted activity (or direct posting). entry_id is the ledger sequence: storage and commit order
--         only. Calculation orders by (effective_date, source_seq, idempotency_key)  -- OWNER-INPUT: C4.
-- line  : the entry's lines. unit is a currency (CAD, USD) or a security commodity SEC:<securityId>; amount is a
--         positive bigint in minor units or share units (OWNER-INPUT: C3 share scale 10^-6). No floating point.
--
-- Immutability (ADR 0003, draft ADR 0005): role ledgerline_app has SELECT and INSERT only, and a statement trigger
-- raises "append-only" on UPDATE, DELETE or TRUNCATE for every role, including the table owner.

-- The application role. Roles are cluster-wide, so creation tolerates an existing role (one cluster, many databases).
DO $$
BEGIN
    CREATE ROLE ledgerline_app NOLOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE;
EXCEPTION
    WHEN duplicate_object OR unique_violation THEN
        RAISE NOTICE 'role ledgerline_app already exists';
END
$$;

CREATE TABLE ledger.entry (
    entry_id        bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    idempotency_key text   NOT NULL UNIQUE CHECK (idempotency_key <> ''),
    effective_date  date   NOT NULL,
    source_seq      bigint NOT NULL
);

CREATE TABLE ledger.line (
    line_id  bigint  GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    entry_id bigint  NOT NULL REFERENCES ledger.entry (entry_id),
    line_no  integer NOT NULL CHECK (line_no > 0),
    account  text    NOT NULL CHECK (account <> ''),
    unit     text    NOT NULL CHECK (unit <> ''),
    side     text    NOT NULL CHECK (side IN ('DEBIT', 'CREDIT')),
    amount   bigint  NOT NULL CHECK (amount > 0),
    UNIQUE (entry_id, line_no)
);

CREATE FUNCTION ledger.reject_mutation() RETURNS trigger
    LANGUAGE plpgsql AS
$$
BEGIN
    RAISE EXCEPTION 'ledger.% is append-only: % is not allowed', TG_TABLE_NAME, TG_OP
        USING HINT = 'Correct with a reversal plus a new activity (ADR 0003).';
END
$$;

CREATE TRIGGER entry_append_only
    BEFORE UPDATE OR DELETE OR TRUNCATE ON ledger.entry
    FOR EACH STATEMENT EXECUTE FUNCTION ledger.reject_mutation();

CREATE TRIGGER line_append_only
    BEFORE UPDATE OR DELETE OR TRUNCATE ON ledger.line
    FOR EACH STATEMENT EXECUTE FUNCTION ledger.reject_mutation();

REVOKE ALL ON ledger.entry, ledger.line FROM PUBLIC;
GRANT USAGE ON SCHEMA ledger TO ledgerline_app;
GRANT SELECT, INSERT ON ledger.entry, ledger.line TO ledgerline_app;
GRANT USAGE ON ALL SEQUENCES IN SCHEMA ledger TO ledgerline_app;
-- A no-op on a new role; states that the app role never holds these, even if the role pre-existed.
REVOKE UPDATE, DELETE, TRUNCATE ON ledger.entry, ledger.line FROM ledgerline_app;
