package dev.ledgerline.testsupport

import dev.ledgerline.app.ImportPreview
import dev.ledgerline.app.IngestionService
import dev.ledgerline.app.PreviewReport
import dev.ledgerline.app.importPreviewModule
import dev.ledgerline.app.ingestionModule
import dev.ledgerline.calc.PostedActivity
import dev.ledgerline.ingest.CanonicalCsv
import dev.ledgerline.ledger.PayloadHash
import io.ktor.client.HttpClient
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.security.MessageDigest

/**
 * L-20 upload preview (docs/stories/L-20-upload-preview.md). PROVISIONAL: the story is a Draft with open owner
 * decisions. The tests assert NO choice for these (each is neutral, or not tested):
 * - token versus stateless confirm: nothing asserts that the server keeps or lacks preview state;
 * - maximum file size and row cap: only a body above 32 MB (today's cap, which the owner may only lower) is refused;
 *   no test sends a body between a lower cap and 32 MB, and no test sends more rows than any proposed row cap;
 * - partial versus all-or-nothing confirm: a confirm of a file that has a conflict or a rejected row is only checked
 *   for what both choices share (nothing is overwritten, a conflicting or rejected row is never posted, `posted`
 *   equals the entries gained); the exact "posted 1 and the rest reported" assertion is made on clean files only.
 *   When the owner decides, add the mixed-file assertion in `L20ConfirmSpec`;
 * - duplicates inside one file: only a differential test (preview equals what `importCsv` does), never a fixed answer;
 * - which warnings count as caused by the import: only a file on an empty ledger is tested, the "already present in
 *   the ledger" case is not;
 * - conflict code name: the code of the existing service, `IDEMPOTENCY_KEY_MISMATCH`, in [CONFLICT_CODE] (one place);
 * - endpoint shape: every path, field and query name below is the story's proposal, in [PREVIEW_PATH], [CONFIRM_PATH]
 *   and the helper functions here; a rename is a one-place change;
 * - read-only enforcement: the technique (read-only transaction, role, or code) is not asserted. The test makes every
 *   INSERT on every ledger table fail with a trigger and requires the preview to still answer.
 *
 * No tax value is involved. Every expected value is a count, a line number, a code or an id from the story or from the
 * L-01 fixtures (`tests/fixtures/l01/ac1_valid.csv`, `ac2_bad_rows.csv`).
 *
 * API CONTRACT (PROPOSED by QE; Dev may add members, not change these). Package `dev.ledgerline.app`:
 *
 * ```kotlin
 * enum class PreviewStatus { NEW, DUPLICATE, CONFLICT, REJECTED }
 * data class PreviewRow(
 *     val line: Int,            // physical line, header = line 1 (L-01); a row that spans lines is at its first line
 *     val key: String?,         // idempotency key; null allowed only for REJECTED rows
 *     val type: String?,        // ActivityType name; null allowed only for REJECTED rows
 *     val date: String?,        // effective date yyyy-mm-dd; null allowed only for REJECTED rows
 *     val status: PreviewStatus,
 *     val code: String?,        // null for NEW and DUPLICATE; the L-01 rejection code for REJECTED;
 *                               // "IDEMPOTENCY_KEY_MISMATCH" for CONFLICT
 *     val field: String?,       // REJECTED: the L-01 field; otherwise null
 *     val message: String?,     // REJECTED and CONFLICT: non-blank reason; otherwise null
 * )
 * data class PreviewWarning(
 *     val ruleId: String,       // DATA-GAP-OVERSELL or DATA-GAP-TRANSFER-NO-COST (L-16)
 *     val securityId: String,
 *     val text: String,         // the L-16 text
 *     val lines: List<Int>,     // the file lines of the NEW rows that cause it, ascending
 *     val amountMinor: Long,    // 0
 * )
 * data class PreviewReport(
 *     val rowsRead: Int,        // number of data rows; always new + duplicates + conflicts + rejected
 *     val new: Int, val duplicates: Int, val conflicts: Int, val rejected: Int,
 *     val rows: List<PreviewRow>,           // every data row, ascending by line
 *     val warnings: List<PreviewWarning>,   // in memory replay of the NEW rows; empty with fileErrors
 *     val fileErrors: List<dev.ledgerline.ingest.FileError>,
 *     val fileSha256: String,               // SHA-256 of the UTF-8 bytes of the text, 64 lowercase hex
 * )
 * object ImportPreview {
 *     /**
 *      * Pure: no I/O. Parses [csv] with CanonicalCsv, then classifies each good row against [stored], which maps an
 *      * idempotency key to the stored `PayloadHash.of(activity)` or null. Equal hash: DUPLICATE; other hash:
 *      * CONFLICT; no entry: NEW. Parse rejections are REJECTED rows. [existing] are the posted activities of the
 *      * ledger, used only for [PreviewReport.warnings]. Same arguments, equal result.
 *      */
 *     fun classify(
 *         csv: String,
 *         stored: (idempotencyKey: String) -> String?,
 *         existing: List<PostedActivity> = emptyList(),
 *     ): PreviewReport
 * }
 * fun io.ktor.server.application.Application.importPreviewModule(service: IngestionService)
 * ```
 *
 * `importPreviewModule` must install together with `ingestionModule(service)` in one application (no duplicate-plugin
 * clash) and must not change `POST /v1/imports`, `POST /v1/activities` or `GET /v1/activities/{key}`.
 *
 * HTTP (body is the raw CSV text, `Content-Type: text/csv`, as `POST /v1/imports`):
 * - `POST /v1/imports/preview`: 200 `{"fileSha256","rowsRead","new","duplicates","conflicts","rejected",
 *   "rows":[{"line","key","type","date","status","code","field","message"}],"warnings":[{"ruleId","securityId","text","lines":[..],
 *   "amountMinor"}],"fileErrors":[{"code","message","column"}]}`. Counts and lines are JSON integers; absent values are
 *   JSON null or omitted. The body is a pure function of the file and the ledger state: no timestamp, no random id
 *   (byte-identical for two calls). It writes nothing, takes no idempotency key and creates no outbox row.
 *   Body above the cap: 413 `{"code":"PAYLOAD_TOO_LARGE"}`, nothing parsed.
 * - `POST /v1/imports/confirm?fileSha256=<hex>[&previewNew=<line,line,..>]`, body = the same file. `fileSha256` is
 *   required: absent is 400; present but not equal to the SHA-256 of the body is 409 `{"code":"PREVIEW_MISMATCH"}`,
 *   nothing posted. Otherwise it posts through the existing `IngestionService` path and answers 200 with the preview
 *   shape, where each row's `status` is the outcome of this confirm (NEW = posted by this call; a key another client
 *   or an earlier confirm took is DUPLICATE or CONFLICT) and `new` equals `posted`, plus `"posted":n`,
 *   `"changedSincePreview":bool` and `"changedRows":[{"line","previewStatus","status"}]`. `previewNew` is the list of
 *   lines the client saw as NEW; QE PROPOSAL, needed because a stateless confirm cannot otherwise know what the
 *   preview showed (see the report). With `previewNew`, `changedSincePreview` is true exactly when the set of lines now
 *   NEW differs, and `changedRows` lists every line whose status is no longer the previewed one (a previewed NEW that
 *   is not NEW now), ascending; without it, `changedSincePreview` is false and `changedRows` empty.
 *
 * UI contract: see `L20UiGuardSpec`.
 */
object L20 {
    const val PREVIEW_PATH = "/v1/imports/preview"
    const val CONFIRM_PATH = "/v1/imports/confirm"
    const val CONFLICT_CODE = "IDEMPOTENCY_KEY_MISMATCH"
    const val OVERSELL = "DATA-GAP-OVERSELL"
    const val NO_COST = "DATA-GAP-TRANSFER-NO-COST"

    /** The L-01 AC1 file (3 rows: lines 2, 3, 4) and the L-01 AC2 file (8 bad rows, then 1 good row on line 10). */
    val ac1: String = Fixtures.text("l01/ac1_valid.csv")
    val ac2Bad: String = Fixtures.text("l01/ac2_bad_rows.csv")

    /** The three AC1 data lines, without the header and without line breaks. */
    val ac1Rows: List<String> = ac1.lines().drop(1).filter { it.isNotBlank() }

    val ac1Keys = listOf("TP-0001-2025-0001", "TP-0001-2025-0002", "TP-0001-2025-0003")

    /** A valid BUY row with [key] (TP-0001, SYN-AAA, 10 shares, 301.25 CAD, 2025-02-05); columns can be overridden. */
    fun row(
        key: String,
        vararg overrides: Pair<String, String>,
    ): String = CsvRows.row("idempotency_key" to key, *overrides)

    /** The canonical header and then [rows], one per line, LF after each. */
    fun file(vararg rows: String): String = CsvRows.file(*rows)

    /** The 2026 line of AC2 (b): key TP-0001-2026-0001, BUY, 2026-01-06, SYN-AAA, 10, CAD, 100.00. */
    val row2026: String =
        row("TP-0001-2026-0001", "effective_date" to "2026-01-06", "amount" to "100.00")

    fun sha256(text: String): String =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    /** Key to stored payload hash for every good row of [csv]: what a ledger holding exactly that file would return. */
    fun stored(csv: String): Map<String, String> =
        CanonicalCsv.parse(csv).activities.associate { it.idempotencyKey to PayloadHash.of(it) }

    fun classify(
        csv: String,
        stored: Map<String, String> = emptyMap(),
        existing: List<PostedActivity> = emptyList(),
    ): PreviewReport = ImportPreview.classify(csv, { stored[it] }, existing)

    // ---------- HTTP ----------

    /** One response: status and raw body; the JSON views throw if the body is not a JSON object. */
    class Resp(val status: HttpStatusCode, val text: String) {
        val json: JsonObject by lazy { Json.parseToJsonElement(text).jsonObject }

        fun int(name: String): Int = json.getValue(name).jsonPrimitive.int

        fun str(name: String): String = json.getValue(name).jsonPrimitive.content

        fun code(): String? = (json["code"] as? kotlinx.serialization.json.JsonPrimitive)?.content

        fun rows(): List<RowView> = json.getValue("rows").jsonArray.map { RowView(it.jsonObject) }

        fun warnings(): List<JsonObject> = json.getValue("warnings").jsonArray.map { it.jsonObject }

        fun fileErrors(): List<JsonObject> = json.getValue("fileErrors").jsonArray.map { it.jsonObject }

        /** The status of each line, ascending by line, as `line to status`. */
        fun statuses(): List<Pair<Int, String>> = rows().map { it.line to it.status }

        fun fileErrorCodes(): List<String> = fileErrors().map { it.getValue("code").jsonPrimitive.content }

        fun fileErrorColumns(): List<String> = fileErrors().map { it.getValue("column").jsonPrimitive.content }

        fun warningViews(): List<WarningView> =
            warnings().map { w ->
                WarningView(
                    w.getValue("ruleId").jsonPrimitive.content,
                    w.getValue("securityId").jsonPrimitive.content,
                    w.getValue("lines").jsonArray.map { it.jsonPrimitive.int },
                    w.getValue("amountMinor").jsonPrimitive.content.toLong(),
                )
            }

        fun changedRows(): List<JsonObject> = json.getValue("changedRows").jsonArray.map { it.jsonObject }

        fun counts(): List<Int> = listOf("rowsRead", "new", "duplicates", "conflicts", "rejected").map { int(it) }
    }

    data class WarningView(val ruleId: String, val securityId: String, val lines: List<Int>, val amountMinor: Long)

    class RowView(private val o: JsonObject) {
        private fun s(name: String) =
            (o[name] as? kotlinx.serialization.json.JsonPrimitive)?.takeIf { it.isString }?.content

        val line: Int = o.getValue("line").jsonPrimitive.int
        val key: String? = s("key")
        val status: String = s("status")!!
        val code: String? = s("code")
        val field: String? = s("field")
        val message: String? = s("message")
    }

    // ---------- Database ----------

    /** Makes every INSERT on every ledger table fail, so a preview that tries to write cannot succeed. */
    fun LedgerDb.failEveryInsert() {
        owner.connection.use { c ->
            c.createStatement().use { st ->
                st.execute(
                    "CREATE OR REPLACE FUNCTION ledger.l20_no_insert() RETURNS trigger LANGUAGE plpgsql AS " +
                        "\$\$ BEGIN RAISE EXCEPTION 'L20 insert during a read-only call'; END \$\$",
                )
                tables().forEach {
                    st.execute(
                        "CREATE TRIGGER l20_no_insert BEFORE INSERT ON ledger.$it " +
                            "FOR EACH ROW EXECUTE FUNCTION ledger.l20_no_insert()",
                    )
                }
            }
        }
    }

    fun LedgerDb.allowInserts() {
        owner.connection.use { c ->
            c.createStatement().use { st ->
                tables().forEach { st.execute("DROP TRIGGER IF EXISTS l20_no_insert ON ledger.$it") }
            }
        }
    }
}

/** Runs [block] against a fresh ledger whose app has the existing ingestion routes and the L-20 routes. */
suspend fun PostgresLedger.withL20Api(block: suspend ApplicationTestBuilder.(L02Env, HttpClient) -> Unit) {
    val e = l02Env()
    testApplication {
        application {
            ingestionModule(e.service)
            importPreviewModule(e.service)
        }
        block(e, client)
    }
}

suspend fun HttpClient.preview(
    body: String,
    path: String = L20.PREVIEW_PATH,
): L20.Resp =
    post(path) {
        contentType(ContentType.Text.CSV)
        setBody(body)
    }.let { L20.Resp(it.status, it.bodyAsText()) }

suspend fun HttpClient.previewBytes(body: ByteArray): L20.Resp =
    post(L20.PREVIEW_PATH) {
        contentType(ContentType.Text.CSV)
        setBody(body)
    }.let { L20.Resp(it.status, it.bodyAsText()) }

suspend fun HttpClient.confirm(
    body: String,
    fileSha256: String?,
    previewNew: List<Int>? = null,
): L20.Resp =
    post(L20.CONFIRM_PATH) {
        if (fileSha256 != null) parameter("fileSha256", fileSha256)
        if (previewNew != null) parameter("previewNew", previewNew.joinToString(","))
        contentType(ContentType.Text.CSV)
        setBody(body)
    }.let { L20.Resp(it.status, it.bodyAsText()) }

/** Confirms [body] the way a client does: with the hash and the NEW lines of [preview]. */
suspend fun HttpClient.confirmAfter(
    body: String,
    preview: L20.Resp,
): L20.Resp = confirm(body, preview.str("fileSha256"), preview.rows().filter { it.status == "NEW" }.map { it.line })

/** The entry ids per key from the stored activities, for "exactly one entry per key". */
fun L02Env.entryKeys(): List<String> = repo.entries().map { it.idempotencyKey }

fun IngestionService.amountOf(key: String): Long? = find(key)?.activity?.amountMinor
