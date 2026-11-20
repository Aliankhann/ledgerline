-- V8 (story L-05, bugs B-016 and B-017): parked outbox events.
--
-- OWNER-INPUT: C27 (placeholder). An outbox event the relay can never publish is recorded here, once, with a reason,
-- instead of being retried forever:
--   PAYLOAD_UNREADABLE  the stored body is not a JSON object (B-017)
--   LEGACY_UNREADABLE   a pre-V7 row whose activity row is missing, so its event cannot be built (B-017)
--   EVENT_TOO_LARGE     the message value is over the relay's configured maximum (B-016)
--   RECORD_TOO_LARGE    the producer or the broker refused the record as too large (B-016)
-- A parked event stays pending (it has no ledger.outbox_delivery row) and it holds back every later event of the same
-- taxpayer, so a consumer never sees a taxpayer's events past a gap (AC7). Until the owner decides C27 nothing
-- releases a parked event; other taxpayers keep flowing. taxpayer_id is NULL only when the row's key cannot be read.
--
-- Same guarantees as V2-V7: append-only for every role (statement trigger), the app role holds SELECT and INSERT only,
-- no FOREIGN KEY (the existence trigger stands in for it). Two relays may park the same event; ON CONFLICT DO NOTHING
-- keeps the first row.

CREATE TABLE ledger.outbox_parked (
    outbox_id   bigint      PRIMARY KEY,
    taxpayer_id text        CHECK (taxpayer_id <> ''),
    reason_code text        NOT NULL CHECK (reason_code IN
                                ('PAYLOAD_UNREADABLE', 'LEGACY_UNREADABLE', 'EVENT_TOO_LARGE', 'RECORD_TOO_LARGE')),
    detail      text        NOT NULL,
    parked_at   timestamptz NOT NULL DEFAULT now()
);

-- The relay's pending query asks "is there a parked event of this taxpayer at or before this one?".
CREATE INDEX outbox_parked_taxpayer ON ledger.outbox_parked (taxpayer_id, outbox_id);

CREATE TRIGGER outbox_parked_append_only
    BEFORE UPDATE OR DELETE OR TRUNCATE ON ledger.outbox_parked
    FOR EACH STATEMENT EXECUTE FUNCTION ledger.reject_mutation();

CREATE FUNCTION ledger.outbox_parked_checks() RETURNS trigger
    LANGUAGE plpgsql
    SET search_path = pg_catalog, pg_temp AS
$$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM ledger.outbox o WHERE o.outbox_id = NEW.outbox_id) THEN
        RAISE EXCEPTION 'ledger.outbox_parked: outbox row % does not exist', NEW.outbox_id
            USING ERRCODE = 'foreign_key_violation';
    END IF;
    RETURN NULL;
END
$$;

CREATE TRIGGER outbox_parked_checks
    AFTER INSERT ON ledger.outbox_parked
    FOR EACH ROW EXECUTE FUNCTION ledger.outbox_parked_checks();

REVOKE ALL ON ledger.outbox_parked FROM PUBLIC;
GRANT SELECT, INSERT ON ledger.outbox_parked TO ledgerline_app;
REVOKE UPDATE, DELETE, TRUNCATE ON ledger.outbox_parked FROM ledgerline_app;
