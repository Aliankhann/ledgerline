package dev.ledgerline.ledger

/**
 * Append-only double-entry ledger: [Posting] rules, [LedgerRepository] storage and [LedgerMigrations].
 * Idempotency, reversals and the outbox arrive with L-02, L-04 and L-05.
 */
object Module {
    const val NAME = "ledger"
}
