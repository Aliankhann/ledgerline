-- V7 (story L-05): reliable event publication through the transactional outbox.
--
-- ledger.outbox (V4) gains the event each row publishes, written in the same transaction as its entry:
--   event_type  ACTIVITY_POSTED, or ENTRY_REVERSED for a reversal (OWNER-INPUT: C25: reversals and corrections write
--               outbox events too)
--   taxpayer_id the message key (OWNER-INPUT: C26); for a reversal, the reversed entry's taxpayer
--   payload     the event body as JSON text, without eventId; the relay adds eventId = outbox_id when it sends, so
--               every copy of an event is byte-identical (text, not jsonb: jsonb would reorder keys)
--   activity_id is now nullable: reversals and correction replacements have no ledger.activity row.
-- Rows written before V7 keep NULL in the new columns; OutboxStore derives their event from ledger.activity. The
-- CHECK below is NOT VALID so that it binds every new row without scanning (or needing to UPDATE) the old ones.
--
-- ledger.outbox_delivery (OWNER-INPUT: C5): one row per event the broker acknowledged. "Sent" is the existence of
-- this row; the outbox row is never updated. Pending = outbox minus delivery, an anti-join on the primary key.
-- ON CONFLICT DO NOTHING lets two relays race without an error and without an UPDATE.
--
-- Same guarantees as V2-V6: append-only for every role (statement trigger), the app role holds SELECT and INSERT
-- only, and no FOREIGN KEY (a key would make TRUNCATE fail with an FK error before the append-only trigger); triggers
-- check existence instead. An outbox row is sealed to its entry: it can only be written in the entry's own
-- transaction, so a committed entry can never be republished under a new event id.
--
-- Ordering: outbox rows are inserted while LedgerRepository holds the append lock until commit, and outbox_id comes
-- from an identity with CACHE 1, so event ids increase in commit order. The relay relies on this for per-taxpayer
-- order and for skipping a fully delivered prefix.

ALTER TABLE ledger.outbox ALTER COLUMN activity_id DROP NOT NULL;

ALTER TABLE ledger.outbox
    ADD COLUMN event_type  text CHECK (event_type IN ('ACTIVITY_POSTED', 'ENTRY_REVERSED')),
    ADD COLUMN taxpayer_id text CHECK (taxpayer_id <> ''),
    ADD COLUMN payload     text CHECK (payload LIKE '{%}');

ALTER TABLE ledger.outbox
    ADD CONSTRAINT outbox_event_complete
        CHECK (event_type IS NOT NULL AND taxpayer_id IS NOT NULL AND payload IS NOT NULL) NOT VALID;

CREATE FUNCTION ledger.outbox_checks() RETURNS trigger
    LANGUAGE plpgsql
    SET search_path = pg_catalog, pg_temp AS
$$
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM ledger.entry e
        JOIN ledger.outbox o ON o.entry_id = e.entry_id
        WHERE o.outbox_id = NEW.outbox_id AND e.xmin = o.xmin
    ) THEN
        RAISE EXCEPTION 'ledger.outbox: entry % was not created in this transaction; a committed entry is sealed',
            NEW.entry_id
            USING ERRCODE = 'check_violation';
    END IF;
    -- OWNER-INPUT: C25: a reversal publishes ENTRY_REVERSED, anything else ACTIVITY_POSTED. The link row is written
    -- before the outbox row (LedgerRepository), so it is visible here.
    IF (NEW.event_type = 'ENTRY_REVERSED') IS DISTINCT FROM
       (EXISTS (SELECT 1 FROM ledger.reversal r WHERE r.reversal_entry_id = NEW.entry_id)) THEN
        RAISE EXCEPTION 'ledger.outbox: event type % does not match entry %', NEW.event_type, NEW.entry_id
            USING ERRCODE = 'check_violation';
    END IF;
    RETURN NULL;
END
$$;

CREATE TRIGGER outbox_checks
    AFTER INSERT ON ledger.outbox
    FOR EACH ROW EXECUTE FUNCTION ledger.outbox_checks();

-- OWNER-INPUT: C5 (placeholder): delivery state is this append-only table, never an UPDATE of ledger.outbox.
CREATE TABLE ledger.outbox_delivery (
    outbox_id       bigint      PRIMARY KEY,
    topic           text        NOT NULL CHECK (topic <> ''),
    kafka_partition integer     NOT NULL CHECK (kafka_partition >= 0),
    kafka_offset    bigint      NOT NULL CHECK (kafka_offset >= 0),
    delivered_at    timestamptz NOT NULL DEFAULT now()
);

CREATE TRIGGER outbox_delivery_append_only
    BEFORE UPDATE OR DELETE OR TRUNCATE ON ledger.outbox_delivery
    FOR EACH STATEMENT EXECUTE FUNCTION ledger.reject_mutation();

CREATE FUNCTION ledger.outbox_delivery_checks() RETURNS trigger
    LANGUAGE plpgsql
    SET search_path = pg_catalog, pg_temp AS
$$
BEGIN
    -- Without this, a delivery row written ahead of its outbox row would hide that event forever.
    IF NOT EXISTS (SELECT 1 FROM ledger.outbox o WHERE o.outbox_id = NEW.outbox_id) THEN
        RAISE EXCEPTION 'ledger.outbox_delivery: outbox row % does not exist', NEW.outbox_id
            USING ERRCODE = 'foreign_key_violation';
    END IF;
    RETURN NULL;
END
$$;

CREATE TRIGGER outbox_delivery_checks
    AFTER INSERT ON ledger.outbox_delivery
    FOR EACH ROW EXECUTE FUNCTION ledger.outbox_delivery_checks();

REVOKE ALL ON ledger.outbox_delivery FROM PUBLIC;
GRANT SELECT, INSERT ON ledger.outbox_delivery TO ledgerline_app;
REVOKE UPDATE, DELETE, TRUNCATE ON ledger.outbox_delivery FROM ledgerline_app;
