-- V6 (story L-04, bug B-008): refuse a reversal chain whichever order the link rows are written in.
--
-- V5 checked only that NEW.original_entry_id is not already a reversal. Linking R2 -> R before R -> A therefore
-- slipped through: when R2 -> R was written R was not yet a reversal, and nothing checked, when R -> A was written,
-- that R is already someone's original. Both directions are checked now, on every link row, so the second row of any
-- chain A <- R <- R2 fails inside the same transaction, whichever was inserted first (OWNER-INPUT: C16).
-- V5 is not edited; the function is replaced and its trigger keeps pointing at it.

CREATE OR REPLACE FUNCTION ledger.reversal_checks() RETURNS trigger
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
    -- OWNER-INPUT: C16 (placeholder): reversing a reversal is refused, in either insert order.
    IF EXISTS (SELECT 1 FROM ledger.reversal r WHERE r.reversal_entry_id = NEW.original_entry_id) THEN
        RAISE EXCEPTION 'ledger.reversal: entry % is itself a reversal and cannot be reversed', NEW.original_entry_id
            USING ERRCODE = 'check_violation';
    END IF;
    IF EXISTS (SELECT 1 FROM ledger.reversal r WHERE r.original_entry_id = NEW.reversal_entry_id) THEN
        RAISE EXCEPTION 'ledger.reversal: entry % is already reversed, so it cannot itself be a reversal',
            NEW.reversal_entry_id
            USING ERRCODE = 'check_violation';
    END IF;
    RETURN NULL;
END
$$;
