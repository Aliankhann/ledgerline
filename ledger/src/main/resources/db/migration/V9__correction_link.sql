-- V9 (bug B-029, draft ADR 0007): links a correction's reversal to its replacement.
--
-- LedgerRepository.correct writes a reversal and a replacement in one transaction. The consumer must judge the pair as
-- one unit, so the pair is recorded here. A reversal posted on its own has no row. Same append-only rules as
-- ledger.reversal: no foreign keys (see V5), UPDATE/DELETE/TRUNCATE refused by trigger and revoked from the app role.

CREATE TABLE ledger.correction (
    reversal_entry_id    bigint PRIMARY KEY,
    replacement_entry_id bigint NOT NULL UNIQUE,
    CHECK (replacement_entry_id > reversal_entry_id)
);

CREATE TRIGGER correction_append_only
    BEFORE UPDATE OR DELETE OR TRUNCATE ON ledger.correction
    FOR EACH STATEMENT EXECUTE FUNCTION ledger.reject_mutation();

CREATE FUNCTION ledger.correction_checks() RETURNS trigger
    LANGUAGE plpgsql
    SET search_path = pg_catalog, pg_temp AS
$$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM ledger.reversal r WHERE r.reversal_entry_id = NEW.reversal_entry_id)
       OR NOT EXISTS (SELECT 1 FROM ledger.entry e WHERE e.entry_id = NEW.replacement_entry_id) THEN
        RAISE EXCEPTION 'ledger.correction: entries %, % must be an existing reversal and entry',
            NEW.reversal_entry_id, NEW.replacement_entry_id
            USING ERRCODE = 'foreign_key_violation';
    END IF;
    RETURN NULL;
END
$$;

CREATE TRIGGER correction_checks
    AFTER INSERT ON ledger.correction
    FOR EACH ROW EXECUTE FUNCTION ledger.correction_checks();

REVOKE ALL ON ledger.correction FROM PUBLIC;
GRANT SELECT, INSERT ON ledger.correction TO ledgerline_app;
REVOKE UPDATE, DELETE, TRUNCATE ON ledger.correction FROM ledgerline_app;
