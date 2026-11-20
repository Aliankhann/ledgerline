-- B-024: a reversal can leave a (taxpayer, security) with no effective activity, so its derived pool row is removed.
-- calc.pool is derived state, rebuildable by replay (see V1), so unlike the ledger it may be deleted from.
GRANT DELETE ON calc.pool TO ledgerline_app;
