package dev.ledgerline.ledger

/**
 * Append-only double-entry ledger: [Posting] rules, [LedgerRepository] storage and [LedgerMigrations].
 * Idempotent submission (L-02) is [LedgerRepository.submit]; reversals and outbox delivery arrive with L-04 and L-05.
 */
object Module {
    const val NAME = "ledger"
}
