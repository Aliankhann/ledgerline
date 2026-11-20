package dev.ledgerline.app

import dev.ledgerline.core.Activity
import dev.ledgerline.ingest.CanonicalCsv
import dev.ledgerline.ingest.FileError
import dev.ledgerline.ingest.Rejection
import dev.ledgerline.ledger.LedgerRepository
import dev.ledgerline.ledger.SubmitOutcome
import dev.ledgerline.ledger.ZeroAmountException
import dev.ledgerline.ledger.outbox.EventTooLargeException
import java.time.Clock
import java.time.Instant

sealed interface SubmitResult {
    /** New: [alreadyExisted] is false. Duplicate: true, with the ORIGINAL ids. */
    data class Accepted(val activityId: String, val entryId: Long, val alreadyExisted: Boolean) : SubmitResult

    /** The key exists with a different payload (OWNER-INPUT: C22, code [KEY_MISMATCH]). Nothing was written. */
    data class KeyMismatch(val idempotencyKey: String) : SubmitResult

    /** The ledger refused the activity. Nothing was written and the key is not consumed (OWNER-INPUT: C21). */
    data class Invalid(val code: String, val field: String?) : SubmitResult

    companion object {
        const val KEY_MISMATCH = "IDEMPOTENCY_KEY_MISMATCH"
    }
}

data class StoredActivity(val activityId: String, val entryId: Long, val activity: Activity, val recordedAt: Instant)

/** [rejected] holds parse rejections and key mismatches, by physical line (OWNER-INPUT: C23 partial import). */
data class ImportReport(
    val posted: Int,
    val duplicates: Int,
    val rejected: List<Rejection>,
    val fileErrors: List<FileError>,
)

/**
 * The one funnel every activity enters through (ADR 0001): a key plus a payload hash decide between post, duplicate
 * and mismatch. Thread-safe; the ledger's transaction and unique keys make concurrent calls safe.
 */
class IngestionService(
    private val ledger: LedgerRepository,
    private val clock: Clock = Clock.systemUTC(),
) {
    fun submit(activity: Activity): SubmitResult =
        try {
            when (val outcome = ledger.submit(activity, clock.instant())) {
                is SubmitOutcome.Posted -> SubmitResult.Accepted(outcome.activityId, outcome.entryId, false)
                is SubmitOutcome.Duplicate -> SubmitResult.Accepted(outcome.activityId, outcome.entryId, true)
                SubmitOutcome.Mismatch -> SubmitResult.KeyMismatch(activity.idempotencyKey)
            }
        } catch (e: ZeroAmountException) {
            SubmitResult.Invalid(e.code, "amount")
        } catch (e: EventTooLargeException) {
            // OWNER-INPUT: C27: the published event would be too large for the broker; no single field is to blame.
            SubmitResult.Invalid(e.code, null)
        }

    /**
     * Parses [text] and submits each good row in file order. OWNER-INPUT: C23 partial import: good rows post, bad rows
     * and key mismatches are reported by line. OWNER-INPUT: C24: a key repeated in one file is a duplicate if the
     * payload is identical and a mismatch if not, which falls out of submitting row by row. `sourceSeq` is the row's
     * physical line, so the file's row order is kept (C4) without being part of the payload hash (C20).
     */
    fun importCsv(text: String): ImportReport {
        val parsed = CanonicalCsv.parse(text)
        var posted = 0
        var duplicates = 0
        val rejected = parsed.rejections.toMutableList()
        parsed.activities.zip(parsed.activityLines).forEach { (activity, line) ->
            when (val result = submit(activity.copy(sourceSeq = line.toLong()))) {
                is SubmitResult.Accepted -> if (result.alreadyExisted) duplicates++ else posted++
                is SubmitResult.KeyMismatch ->
                    rejected +=
                        Rejection(line, SubmitResult.KEY_MISMATCH, "idempotency_key", "Key reused with another payload")
                is SubmitResult.Invalid ->
                    rejected += Rejection(line, result.code, result.field.orEmpty(), "Refused: ${result.code}")
            }
        }
        return ImportReport(posted, duplicates, rejected.sortedBy { it.line }, parsed.fileErrors)
    }

    fun find(idempotencyKey: String): StoredActivity? =
        ledger.findActivity(
            idempotencyKey,
        )?.let { StoredActivity(it.activityId, it.entryId, it.activity, it.recordedAt) }
}
