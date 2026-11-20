-- V5 (story L-04): corrections by reversal.
--
-- ledger.reversal links a reversal entry to the entry it reverses. It is the only place a reversal is recorded, so the
-- original entry's rows are never touched: "reversed" is derived by looking for a link row (ADR 0003, draft ADR 0005).
-- The table is append-only like the others (UPDATE, DELETE and TRUNCATE are refused for every role) and the app role
-- holds SELECT and INSERT only.
--
-- Guarantees checked by the database, not only by Kotlin:
--   * one reversal per original (UNIQUE original_entry_id), and an entry cannot reverse itself or a later entry;
--   * the link is written in the same transaction as the reversal entry (a committed entry cannot be linked later);
--   * an entry that is itself a reversal cannot be reversed (OWNER-INPUT: C16, refused at the database too);
--   * at commit, the reversal's lines mirror the original's lines one for one with the opposite side (AC2).
-- There are no FOREIGN KEY constraints on purpose: a foreign key to ledger.entry would make Postgres refuse
-- TRUNCATE ledger.entry, ledger.line before the append-only trigger runs (L-03's guarantee is that every TRUNCATE form
-- fails with "append-only"). The trigger below checks both entries exist instead; entries can never be deleted.
-- The reversal entry itself is an ordinary ledger.entry whose lines pass the V3 sealing and balance checks.

CREATE TABLE ledger.reversal (
    reversal_entry_id bigint PRIMARY KEY,
    original_entry_id bigint NOT NULL UNIQUE,
    reason            text   NOT NULL CHECK (btrim(reason) <> ''),
    CHECK (reversal_entry_id > original_entry_id)
);

CREATE TRIGGER reversal_append_only
    BEFORE UPDATE OR DELETE OR TRUNCATE ON ledger.reversal
    FOR EACH STATEMENT EXECUTE FUNCTION ledger.reject_mutation();

CREATE FUNCTION ledger.reversal_checks() RETURNS trigger
    LANGUAGE plpgsql
    SET search_path = pg_catalog, pg_temp AS
$$
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM ledger.entry e
        JOIN ledger.reversal r ON r.reversal_entry_id = e.entry_id
        WHERE r.reversal_entry_id = NEW.reversal_entry_id AND e.xmin = r.xmin
    ) THEN
        RAISE EXCEPTION 'ledger.reversal: entry % was not created in this transaction; a committed entry is sealed',
            NEW.reversal_entry_id
            USING ERRCODE = 'check_violation';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM ledger.entry e WHERE e.entry_id = NEW.original_entry_id) THEN
        RAISE EXCEPTION 'ledger.reversal: entry % does not exist', NEW.original_entry_id
            USING ERRCODE = 'foreign_key_violation';
    END IF;
    -- OWNER-INPUT: C16 (placeholder): reversing a reversal is refused.
    IF EXISTS (SELECT 1 FROM ledger.reversal r WHERE r.reversal_entry_id = NEW.original_entry_id) THEN
        RAISE EXCEPTION 'ledger.reversal: entry % is itself a reversal and cannot be reversed', NEW.original_entry_id
            USING ERRCODE = 'check_violation';
    END IF;
    RETURN NULL;
END
$$;

CREATE FUNCTION ledger.reversal_mirrors_original() RETURNS trigger
    LANGUAGE plpgsql
    SET search_path = pg_catalog, pg_temp AS
$$
BEGIN
    IF EXISTS (
        (SELECT l.line_no, l.account, l.unit, l.amount,
                CASE l.side WHEN 'DEBIT' THEN 'CREDIT' ELSE 'DEBIT' END
         FROM ledger.line l WHERE l.entry_id = NEW.original_entry_id
         EXCEPT
         SELECT l.line_no, l.account, l.unit, l.amount, l.side
         FROM ledger.line l WHERE l.entry_id = NEW.reversal_entry_id)
        UNION ALL
        (SELECT l.line_no, l.account, l.unit, l.amount, l.side
         FROM ledger.line l WHERE l.entry_id = NEW.reversal_entry_id
         EXCEPT
         SELECT l.line_no, l.account, l.unit, l.amount,
                CASE l.side WHEN 'DEBIT' THEN 'CREDIT' ELSE 'DEBIT' END
         FROM ledger.line l WHERE l.entry_id = NEW.original_entry_id)
    ) THEN
        RAISE EXCEPTION 'ledger.reversal: entry % does not mirror entry % line for line',
            NEW.reversal_entry_id, NEW.original_entry_id
            USING ERRCODE = 'check_violation';
    END IF;
    RETURN NULL;
END
$$;

CREATE TRIGGER reversal_checks
    AFTER INSERT ON ledger.reversal
    FOR EACH ROW EXECUTE FUNCTION ledger.reversal_checks();

CREATE CONSTRAINT TRIGGER reversal_mirrors_original
    AFTER INSERT ON ledger.reversal
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION ledger.reversal_mirrors_original();

REVOKE ALL ON ledger.reversal FROM PUBLIC;
GRANT SELECT, INSERT ON ledger.reversal TO ledgerline_app;
REVOKE UPDATE, DELETE, TRUNCATE ON ledger.reversal FROM ledgerline_app;
