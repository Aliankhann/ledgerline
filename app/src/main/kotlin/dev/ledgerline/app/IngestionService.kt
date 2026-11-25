package dev.ledgerline.app

import dev.ledgerline.calc.PostedActivity
import dev.ledgerline.core.Activity
import dev.ledgerline.ingest.BrokerFormats
import dev.ledgerline.ingest.CanonicalCsv
import dev.ledgerline.ingest.FileError
import dev.ledgerline.ingest.ImportDefaults
import dev.ledgerline.ingest.ParseResult
import dev.ledgerline.ingest.Rejection
import dev.ledgerline.ledger.BatchResult
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
    fun submit(activity: Activity): SubmitResult = submitWith(activity)

    /** B-038: account ids the ledger refuses are checked first, so they are an [SubmitResult.Invalid], not an exception. */
    private fun submitWith(activity: Activity): SubmitResult {
        ImportPreview.accountRefusal(activity)?.let { return it }
        return resultOf(activity, BatchResult.of { ledger.submit(activity, clock.instant()) })
    }

    private fun resultOf(
        activity: Activity,
        result: BatchResult,
    ): SubmitResult =
        when (result) {
            is BatchResult.Done ->
                when (val outcome = result.outcome) {
                    is SubmitOutcome.Posted -> SubmitResult.Accepted(outcome.activityId, outcome.entryId, false)
                    is SubmitOutcome.Duplicate -> SubmitResult.Accepted(outcome.activityId, outcome.entryId, true)
                    SubmitOutcome.Mismatch -> SubmitResult.KeyMismatch(activity.idempotencyKey)
                }
            is BatchResult.Refused ->
                when (val e = result.cause) {
                    is ZeroAmountException -> SubmitResult.Invalid(e.code, "amount")
                    // OWNER-INPUT: C27: the published event would be too large for the broker; no single field is to blame.
                    is EventTooLargeException -> SubmitResult.Invalid(e.code, null)
                    else -> throw e
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
        val rows = submitRows(parsed) { acts -> ledger.submitAll(acts, clock.instant()) }
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
    fun confirm(
        text: String,
        defaults: ImportDefaults? = null,
    ): PreviewReport {
        val parsed = BrokerFormats.parse(text, defaults)
        val rows =
            ledger.withImportLock {
                    submitter ->
                submitRows(parsed) { submitter.submitAll(it, clock.instant()) }
            }
        return PreviewReport.of(
            rows,
            emptyList(),
            parsed.fileErrors,
            ImportPreview.sha256(text),
            BrokerFormats.detectFile(text),
        )
    }

    /**
     * L-20 preview: reads on one read-only transaction and classifies in memory ([ImportPreview.classify]). Writes
     * nothing and consumes no key.
     */
    fun preview(
        text: String,
        defaults: ImportDefaults? = null,
    ): PreviewReport {
        val parsed = BrokerFormats.parse(text, defaults)
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
            BrokerFormats.detectFile(text),
        )
    }

    /**
     * One result row per file row (good rows submitted in file order, in chunks, parse rejections as they are), by
     * line. Refused account ids are answered before the ledger is asked (B-038).
     */
    private fun submitRows(
        parsed: ParseResult,
        submitAll: (List<Activity>) -> List<BatchResult>,
    ): List<PreviewRow> {
        val rows = parsed.rejections.map { ImportPreview.rejectedRow(it) }.toMutableList()
        val items =
            parsed.activities.zip(parsed.activityLines).map {
                    (a, line) ->
                a.copy(sourceSeq = line.toLong()) to line
            }
        val refusals = items.map { ImportPreview.accountRefusal(it.first) }
        val sendable = items.indices.filter { refusals[it] == null }
        val sent = submitAll(sendable.map { items[it].first })
        val results = refusals.toMutableList<SubmitResult?>()
        sendable.forEachIndexed { k, i -> results[i] = resultOf(items[i].first, sent[k]) }
        items.forEachIndexed { i, (activity, line) ->
            rows +=
                when (val result = results[i]!!) {
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
