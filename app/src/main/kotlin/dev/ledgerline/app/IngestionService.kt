package dev.ledgerline.app

import dev.ledgerline.calc.PostedActivity
import dev.ledgerline.core.Activity
import dev.ledgerline.ingest.CanonicalCsv
import dev.ledgerline.ingest.FileError
import dev.ledgerline.ingest.ParseResult
import dev.ledgerline.ingest.Rejection
import dev.ledgerline.ledger.LedgerRepository
import dev.ledgerline.ledger.SubmitOutcome
import dev.ledgerline.ledger.Submitter
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
    fun submit(activity: Activity): SubmitResult = submitWith(activity) { a, at -> ledger.submit(a, at) }

    /** B-038: account ids the ledger refuses are checked first, so they are an [SubmitResult.Invalid], not an exception. */
    private fun submitWith(
        activity: Activity,
        submitter: Submitter,
    ): SubmitResult {
        ImportPreview.accountRefusal(activity)?.let { return it }
        return try {
            when (val outcome = submitter.submit(activity, clock.instant())) {
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
    }

    /**
     * Parses [text] and submits each good row in file order. OWNER-INPUT: C23 partial import: good rows post, bad rows
     * and key mismatches are reported by line. OWNER-INPUT: C24: a key repeated in one file is a duplicate if the
     * payload is identical and a mismatch if not, which falls out of submitting row by row. `sourceSeq` is the row's
     * physical line, so the file's row order is kept (C4) without being part of the payload hash (C20).
     */
    fun importCsv(text: String): ImportReport {
        val parsed = CanonicalCsv.parse(text)
        val rows = submitRows(parsed, ::submit)
        return ImportReport(
            rows.count { it.status == PreviewStatus.NEW },
            rows.count { it.status == PreviewStatus.DUPLICATE },
            rows.filter { it.status == PreviewStatus.CONFLICT || it.status == PreviewStatus.REJECTED }
                .map { Rejection(it.line, it.code.orEmpty(), it.field ?: "idempotency_key", it.message.orEmpty()) },
            parsed.fileErrors,
        )
    }

    /**
     * L-20 confirm: the same row-by-row path as [importCsv] (no second idempotency rule), run while holding the
     * file-import lock, so two confirms of one file are atomic per file: the second sees every key of the first.
     * OWNER-INPUT: L-20 atomic per file through a database advisory lock (not an in-process mutex).
     */
    fun confirm(text: String): PreviewReport {
        val parsed = CanonicalCsv.parse(text)
        val rows = ledger.withImportLock { submitter -> submitRows(parsed) { submitWith(it, submitter) } }
        return PreviewReport.of(rows, emptyList(), parsed.fileErrors, ImportPreview.sha256(text))
    }

    /**
     * L-20 preview: reads on one read-only transaction and classifies in memory ([ImportPreview.classify]). Writes
     * nothing and consumes no key.
     */
    fun preview(text: String): PreviewReport {
        val parsed = CanonicalCsv.parse(text)
        val (hashes, existing) =
            ledger.readOnly { r ->
                r.payloadHashes(parsed.activities.map { it.idempotencyKey }) to
                    r.activities(parsed.activities.map { it.taxpayerId })
            }
        return ImportPreview.classify(
            parsed,
            ImportPreview.sha256(text),
            hashes::get,
            existing.map { PostedActivity(it.activityId, it.entryId, it.activity) },
        )
    }

    /** One result row per file row (good rows submitted in file order, parse rejections as they are), by line. */
    private fun submitRows(
        parsed: ParseResult,
        submitOne: (Activity) -> SubmitResult,
    ): List<PreviewRow> {
        val rows = parsed.rejections.map { ImportPreview.rejectedRow(it) }.toMutableList()
        parsed.activities.zip(parsed.activityLines).forEach { (activity, line) ->
            val result = submitOne(activity.copy(sourceSeq = line.toLong()))
            rows +=
                when (result) {
                    is SubmitResult.Accepted -> {
                        val status = if (result.alreadyExisted) PreviewStatus.DUPLICATE else PreviewStatus.NEW
                        ImportPreview.row(activity, line, status)
                    }
                    is SubmitResult.KeyMismatch -> ImportPreview.conflictRow(activity, line)
                    is SubmitResult.Invalid -> ImportPreview.refusedRow(activity, line, result.code, result.field)
                }
        }
        return rows.sortedBy { it.line }
    }

    fun find(idempotencyKey: String): StoredActivity? =
        ledger.findActivity(
            idempotencyKey,
        )?.let { StoredActivity(it.activityId, it.entryId, it.activity, it.recordedAt) }
}
