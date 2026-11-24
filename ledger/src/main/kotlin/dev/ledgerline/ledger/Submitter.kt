package dev.ledgerline.ledger

import dev.ledgerline.core.Activity
import java.time.Instant

/** Submits one activity on a connection the caller already holds; see [LedgerRepository.withImportLock]. */
fun interface Submitter {
    fun submit(
        activity: Activity,
        recordedAt: Instant,
    ): SubmitOutcome
}
