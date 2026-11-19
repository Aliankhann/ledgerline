-- V3 (story L-03, bug B-004): an entry is sealed when its transaction commits.
--
-- 1. Lines can only be added to an entry created by the same (sub)transaction: a line's xmin must equal its entry's
--    xmin. A committed entry's line set can never change, so a later INSERT cannot alter what it says.
-- 2. Lines of an entry are appended in line_id order (identity values from the sequence). This keeps rule 3 to one
--    check per entry and refuses OVERRIDING SYSTEM VALUE ids below an entry's existing lines.
-- 3. At commit, every entry has at least one line and balances per unit (sum of debits = sum of credits). Sums are
--    computed in SQL over bigint, which Postgres widens to an exact numeric; no stored column changes type (AC7).
--
-- Constraint triggers are DEFERRABLE INITIALLY DEFERRED. If a session runs SET CONSTRAINTS ... IMMEDIATE and then
-- adds lines, the newest line's event still fires again at commit, so the final state is always checked.
-- Functions run as the caller; ledgerline_app already has SELECT on both tables (V2).

CREATE INDEX line_entry_line_id ON ledger.line (entry_id, line_id);

CREATE FUNCTION ledger.line_before_insert() RETURNS trigger
    LANGUAGE plpgsql
    SET search_path = pg_catalog, pg_temp AS
$$
BEGIN
    IF EXISTS (SELECT 1 FROM ledger.line l WHERE l.entry_id = NEW.entry_id AND l.line_id >= NEW.line_id) THEN
        RAISE EXCEPTION 'ledger.line: line_id % is not after the existing lines of entry %', NEW.line_id, NEW.entry_id
            USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END
$$;

CREATE FUNCTION ledger.line_same_transaction() RETURNS trigger
    LANGUAGE plpgsql
    SET search_path = pg_catalog, pg_temp AS
$$
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM ledger.entry e
        JOIN ledger.line l ON l.line_id = NEW.line_id
        WHERE e.entry_id = NEW.entry_id AND e.xmin = l.xmin
    ) THEN
        RAISE EXCEPTION 'ledger.line: entry % was not created in this transaction; a committed entry is sealed',
            NEW.entry_id
            USING ERRCODE = 'check_violation',
                HINT = 'Correct with a reversal plus a new activity (ADR 0003).';
    END IF;
    RETURN NULL;
END
$$;

CREATE FUNCTION ledger.line_entry_balanced() RETURNS trigger
    LANGUAGE plpgsql
    SET search_path = pg_catalog, pg_temp AS
$$
DECLARE
    off record;
BEGIN
    -- Only the entry's newest line checks the whole entry; every other line's event is a cheap index lookup.
    IF NEW.line_id <> (SELECT max(l.line_id) FROM ledger.line l WHERE l.entry_id = NEW.entry_id) THEN
        RETURN NULL;
    END IF;
    SELECT l.unit,
           sum(CASE WHEN l.side = 'DEBIT' THEN l.amount ELSE 0 END)
               - sum(CASE WHEN l.side = 'CREDIT' THEN l.amount ELSE 0 END) AS difference
    INTO off
    FROM ledger.line l
    WHERE l.entry_id = NEW.entry_id
    GROUP BY l.unit
    HAVING sum(CASE WHEN l.side = 'DEBIT' THEN l.amount ELSE 0 END)
               <> sum(CASE WHEN l.side = 'CREDIT' THEN l.amount ELSE 0 END)
    ORDER BY l.unit
    LIMIT 1;
    IF FOUND THEN
        RAISE EXCEPTION 'UNBALANCED: entry % is off by % in unit % (debits minus credits)',
            NEW.entry_id, off.difference, off.unit
            USING ERRCODE = 'check_violation';
    END IF;
    RETURN NULL;
END
$$;

CREATE FUNCTION ledger.entry_has_lines() RETURNS trigger
    LANGUAGE plpgsql
    SET search_path = pg_catalog, pg_temp AS
$$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM ledger.line l WHERE l.entry_id = NEW.entry_id) THEN
        RAISE EXCEPTION 'ledger.entry: entry % has no lines', NEW.entry_id
            USING ERRCODE = 'check_violation';
    END IF;
    RETURN NULL;
END
$$;

CREATE TRIGGER line_order
    BEFORE INSERT ON ledger.line
    FOR EACH ROW EXECUTE FUNCTION ledger.line_before_insert();

CREATE TRIGGER line_same_transaction
    AFTER INSERT ON ledger.line
    FOR EACH ROW EXECUTE FUNCTION ledger.line_same_transaction();

CREATE CONSTRAINT TRIGGER line_entry_balanced
    AFTER INSERT ON ledger.line
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION ledger.line_entry_balanced();

CREATE CONSTRAINT TRIGGER entry_has_lines
    AFTER INSERT ON ledger.entry
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION ledger.entry_has_lines();
