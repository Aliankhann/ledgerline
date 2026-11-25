package dev.ledgerline.ledger

import dev.ledgerline.core.Activity
import java.time.Instant

/** Submits activities on a connection the caller already holds; see [LedgerRepository.withImportLock]. */
interface Submitter {
    fun submit(
        activity: Activity,
        recordedAt: Instant,
    ): SubmitOutcome

    /** Per-row results in input order; the default submits row by row. */
    fun submitAll(
        activities: List<Activity>,
        recordedAt: Instant,
    ): List<BatchResult> = activities.map { BatchResult.of { submit(it, recordedAt) } }
}
