package dev.ledgerline.app

import dev.ledgerline.calc.AcbEngine
import dev.ledgerline.calc.CaIta2026v1
import dev.ledgerline.calc.DataGapWarnings
import dev.ledgerline.calc.PoolKey
import dev.ledgerline.calc.PostedActivity
import dev.ledgerline.core.Activity
import dev.ledgerline.ingest.BrokerFormat
import dev.ledgerline.ingest.BrokerFormats
import dev.ledgerline.ingest.FileError
import dev.ledgerline.ingest.ImportDefaults
import dev.ledgerline.ingest.ParseResult
import dev.ledgerline.ingest.Rejection
import dev.ledgerline.ledger.PayloadHash
import dev.ledgerline.ledger.Posting
import dev.ledgerline.ledger.ZeroAmountException
import java.security.MessageDigest

/** L-20: what one row of an uploaded file does (preview) or did (confirm). */
enum class PreviewStatus { NEW, DUPLICATE, CONFLICT, REJECTED }

data class PreviewRow(
    val line: Int,
    val key: String?,
    val type: String?,
    val date: String?,
    val status: PreviewStatus,
    val code: String?,
    val field: String?,
    val message: String?,
)

data class PreviewWarning(
    val ruleId: String,
    val securityId: String,
    val text: String,
    val lines: List<Int>,
    val amountMinor: Long,
)

data class PreviewReport(
    val rowsRead: Int,
    val new: Int,
    val duplicates: Int,
    val conflicts: Int,
    val rejected: Int,
    val rows: List<PreviewRow>,
    val warnings: List<PreviewWarning>,
    val fileErrors: List<FileError>,
    val fileSha256: String,
    val format: BrokerFormat?,
) {
    companion object {
        fun of(
            rows: List<PreviewRow>,
            warnings: List<PreviewWarning>,
            fileErrors: List<FileError>,
            fileSha256: String,
            format: BrokerFormat? = null,
        ): PreviewReport {
            fun count(s: PreviewStatus) = rows.count { it.status == s }
            return PreviewReport(
                rows.size,
                count(PreviewStatus.NEW),
                count(PreviewStatus.DUPLICATE),
                count(PreviewStatus.CONFLICT),
                count(PreviewStatus.REJECTED),
                rows,
                warnings,
                fileErrors,
                fileSha256,
                format,
            )
        }
    }
}

/**
 * L-20 preview classification. Pure: no I/O. It reproduces, row by row, what [IngestionService.importCsv] would do
 * against a ledger holding [stored]: a row the ledger would refuse (zero amount) is REJECTED before its key is looked
 * at, an equal stored hash is a DUPLICATE, another hash a CONFLICT, no entry NEW; a NEW key then counts as stored for
 * the rest of the file (C24).
 */
object ImportPreview {
    fun classify(
        csv: String,
        stored: (idempotencyKey: String) -> String?,
        existing: List<PostedActivity> = emptyList(),
        defaults: ImportDefaults? = null,
    ): PreviewReport =
        classify(
            BrokerFormats.parse(csv, defaults),
            sha256(csv),
            stored,
            existing,
            BrokerFormats.detectFile(csv),
        )

    fun classify(
        parsed: ParseResult,
        fileSha256: String,
        stored: (String) -> String?,
        existing: List<PostedActivity>,
        format: BrokerFormat? = null,
    ): PreviewReport {
        val rows = parsed.rejections.map { rejectedRow(it) }.toMutableList()
        val inFile = mutableMapOf<String, String>()
        val fresh = mutableListOf<PostedActivity>()
        parsed.activities.zip(parsed.activityLines).forEach { (activity, line) ->
            val refusal = refusal(activity)
            val hash = PayloadHash.of(activity)
            val known = inFile[activity.idempotencyKey] ?: stored(activity.idempotencyKey)
            rows +=
                when {
                    refusal != null -> refusedRow(activity, line, refusal.code, refusal.field)
                    known == null -> {
                        inFile[activity.idempotencyKey] = hash
                        val posted = activity.copy(sourceSeq = line.toLong())
                        fresh += PostedActivity("preview-$line", -line.toLong(), posted)
                        row(activity, line, PreviewStatus.NEW)
                    }
                    known == hash -> row(activity, line, PreviewStatus.DUPLICATE)
                    else -> conflictRow(activity, line)
                }
        }
        val warnings = if (parsed.fileErrors.isEmpty()) guardedWarnings(existing, fresh) else emptyList()
        return PreviewReport.of(rows.sortedBy { it.line }, warnings, parsed.fileErrors, fileSha256, format)
    }

    fun sha256(text: String): String =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    internal fun row(
        a: Activity,
        line: Int,
        status: PreviewStatus,
    ) = PreviewRow(line, a.idempotencyKey, a.activityType.name, a.effectiveDate.toString(), status, null, null, null)

    internal fun conflictRow(
        a: Activity,
        line: Int,
    ) = PreviewRow(
        line,
        a.idempotencyKey,
        a.activityType.name,
        a.effectiveDate.toString(),
        PreviewStatus.CONFLICT,
        SubmitResult.KEY_MISMATCH,
        null,
        "Key reused with another payload",
    )

    internal fun refusedRow(
        a: Activity,
        line: Int,
        code: String,
        field: String?,
    ) = PreviewRow(
        line,
        a.idempotencyKey,
        a.activityType.name,
        a.effectiveDate.toString(),
        PreviewStatus.REJECTED,
        code,
        field.orEmpty(),
        "Refused: $code",
    )

    internal fun rejectedRow(r: Rejection) =
        PreviewRow(r.line, null, null, null, PreviewStatus.REJECTED, r.code, r.field, r.message)

    const val ACCOUNT_ID_RESERVED = "ACCOUNT_ID_RESERVED"
    const val ACCOUNT_ID_BLANK = "ACCOUNT_ID_BLANK"
    const val AMOUNT_OUT_OF_RANGE = "AMOUNT_OUT_OF_RANGE"

    /**
     * B-038: the account id checks [Posting.linesFor] enforces with `require`, made up front so a bad id is a refused
     * row (never an exception) in preview, confirm and `POST /v1/imports` alike. Null when the id is acceptable.
     */
    internal fun accountRefusal(a: Activity): SubmitResult.Invalid? =
        when {
            a.accountId.isBlank() -> SubmitResult.Invalid(ACCOUNT_ID_BLANK, "account_id")
            a.accountId.startsWith(
                Posting.SYSTEM_ACCOUNT_PREFIX,
            ) -> SubmitResult.Invalid(ACCOUNT_ID_RESERVED, "account_id")
            else -> null
        }

    /** OWNER-INPUT: L-20 refused rows classify as importCsv treats them. The refusal before the key is read (account id, zero amount C15), or null. */
    private fun refusal(a: Activity): SubmitResult.Invalid? =
        accountRefusal(a) ?: try {
            Posting.linesFor(a)
            null
        } catch (e: ZeroAmountException) {
            SubmitResult.Invalid(e.code, "amount")
        }

    /**
     * B-039: an amount too large for the ACB engine's integer range makes the in-memory replay throw
     * [ArithmeticException] (the engine failing loudly is right, B-007). Rows are still classified; the preview adds one
     * warning, [AMOUNT_OUT_OF_RANGE], naming the cause and every NEW line, so no data-gap check is claimed for the file.
     */
    private fun guardedWarnings(
        existing: List<PostedActivity>,
        fresh: List<PostedActivity>,
    ): List<PreviewWarning> =
        try {
            warningsCaused(existing, fresh)
        } catch (e: ArithmeticException) {
            listOf(
                PreviewWarning(
                    AMOUNT_OUT_OF_RANGE,
                    "",
                    "An amount is too large to calculate (${e.message}); data-gap warnings could not be checked.",
                    fresh.map { (-it.entryId).toInt() }.sorted(),
                    0L,
                ),
            )
        }

    /**
     * L-16 warnings that exist after the NEW rows are added and did not exist before, by pool and rule id (OWNER-INPUT:
     * L-20 "caused by the import"). A NEW row has the synthetic entry id `-line`, so a reason's negative ids are the file
     * lines; if only stored rows are named (a new earlier sale starving a stored one), every NEW line on that security.
     */
    private fun warningsCaused(
        existing: List<PostedActivity>,
        fresh: List<PostedActivity>,
    ): List<PreviewWarning> {
        if (fresh.isEmpty()) return emptyList()
        val engine = AcbEngine(CaIta2026v1, ProductionFx.cache())
        val before = DataGapWarnings.detect(existing, engine.replay(existing))
        val all = existing + fresh
        val after = DataGapWarnings.detect(all, engine.replay(all))
        return after.flatMap { (pool, reasons) ->
            reasons.filter { r -> before[pool].orEmpty().none { it.ruleId == r.ruleId } }
                .map { r ->
                    val lines = r.entryIds.filter { it < 0 }.map { (-it).toInt() }.ifEmpty { newLines(fresh, pool) }
                    PreviewWarning(r.ruleId, pool.securityId, r.text, lines.sorted(), r.amountMinor)
                }
        }.sortedWith(compareBy({ it.ruleId }, { it.securityId }, { it.lines.firstOrNull() }))
    }

    private fun newLines(
        fresh: List<PostedActivity>,
        pool: PoolKey,
    ) = fresh.filter { it.activity.taxpayerId == pool.taxpayerId && it.activity.securityId == pool.securityId }
        .map { (-it.entryId).toInt() }
}
