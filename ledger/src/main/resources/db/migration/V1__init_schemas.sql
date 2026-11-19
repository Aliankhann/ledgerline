-- V1: create the schemas only. Tables arrive with the features that need them
-- (ledger posting on day 3, outbox on day 4, inbox and pools on day 5).
--
-- ledger : append-only source of truth (entries, idempotency keys, outbox)
-- calc   : derived state rebuilt from the ledger (inbox, pools, adjustments)
-- recon  : scratch space for full-replay reconciliation diffs

CREATE SCHEMA IF NOT EXISTS ledger;
CREATE SCHEMA IF NOT EXISTS calc;
CREATE SCHEMA IF NOT EXISTS recon;
