package dev.ledgerline.testsupport

import dev.ledgerline.app.taxYearFromActivities
import dev.ledgerline.calc.PostedActivity
import dev.ledgerline.core.ActivityType
import io.ktor.client.request.request
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import java.io.File
import java.time.LocalDate
import kotlin.random.Random

/**
 * L-18 your tax year (docs/stories/L-18-your-tax-year.md). PROVISIONAL: the story is a Draft and several decisions are
 * the owner's. The tests follow only what is decided; they assert NO choice for: half-inclusion of an odd-cent net
 * (AC3), `DEEMED_GAIN_ROC` rows, per-row rounding beyond a 1-cent tolerance, the CSV money format (the CSV test
 * accepts integer cents `244000` or locale-free dollars `2440.00`), or one note versus one row per superficial-loss
 * trigger beyond "the notes carry the rule id and the entry ids".
 *
 * Tax numbers come only from `tests/fixtures/cra/cra-xyz-gain.properties` (CRA XYZ worked example: gain 244000 cents,
 * taxable portion $1,220) and the share ratio of `cra-ti-2004-0073011e5-partial-sl.properties` (CRA TI
 * 2004-0073011E5). Everything else is a structural property checked against the existing engine's own public replay
 * (`AcbEngine(CaIta2026v1).replaySuperficial`), never a figure from test reasoning.
 *
 * API CONTRACT (PROPOSED by QE; Dev may add members, not change these). Package `dev.ledgerline.calc`, pure, no I/O,
 * no Double/Float/BigDecimal:
 *
 * ```kotlin
 * data class TaxYearRow(
 *     val activityId: String, val taxDate: LocalDate, val securityId: String, val kind: GainKind, val sequence: Long,
 *     val sharesUnits: Long,                  // 10^-6 share, positive; the SELL's quantity (0 for a deemed gain)
 *     val proceedsCents: Long,                // Activity.amountMinor of the SELL (cents, CAD)
 *     val outlaysCents: Long,                 // Activity.commissionMinor of the SELL
 *     val acbSoldCents: Long,                 // toReportedCents(GainLine.acbSold)
 *     val gainCents: Long,                    // reported gain AFTER the superficial-loss denial; negative = loss
 *     val superficialLossDeniedCents: Long,   // >= 0
 *     val notes: List<String>,                // each names its rule id; the SL note has "SL-PARTIAL" (or the rule
 *                                             // id of the outcome) and the decimal entry ids of sale and buys
 *     val ruleIds: List<String>,
 *     val entryIds: List<Long>,               // the SELL's ledger entry id (non-empty)
 *     val substituteEntryIds: List<Long>,     // entry ids of the sale's SlTrigger buys; empty when none
 * )
 * data class TaxYearTotals(
 *     val gainsCents: Long,                   // sum of the positive row gains
 *     val lossesCents: Long,                  // sum of the negative row gains (a negative number)
 *     val netCents: Long,                     // gains + losses = sum of all row gains
 *     val taxableCapitalGainCents: Long,      // TaxYearReport.taxableCents(netCents)
 *     val superficialLossesDeniedCents: Long, // sum of the row amounts (>= 0)
 * )
 * data class TaxYear(
 *     val taxpayerId: String, val year: Int, val rows: List<TaxYearRow>, val totals: TaxYearTotals,
 *     val warnings: List<Reason>,             // the L-16 DataGapWarnings of the taxpayer's securities, all of them
 * )
 * object TaxYearReport {
 *     /** Rows for [taxpayerId] whose tax date (settlement date, else effective date) is in [year]. [activities]
 *      *  may be in any order and may hold other taxpayers. Row order: tax date, sequence, then entry id. */
 *     fun build(activities: List<PostedActivity>, taxpayerId: String, year: Int): TaxYear
 *     /** Years with at least one row for [taxpayerId], newest first. */
 *     fun yearsWithRows(activities: List<PostedActivity>, taxpayerId: String): List<Int>
 *     /** The taxable part of [netCents] at the 1/2 inclusion rate. Only these cases are decided: even net -> net / 2,
 *      *  net <= 0 -> 0. An odd positive net is the owner's decision and is not tested. */
 *     fun taxableCents(netCents: Long): Long
 * }
 * ```
 *
 * Package `dev.ledgerline.app`: `fun Application.taxYearFromActivities(activities: (taxpayer: String) ->
 * List<PostedActivity>)`, called by `Main.kt`, registers three GET routes (nothing else; no write path):
 * - `/v1/tax-year/{taxpayer}` -> 200 `{"taxpayerId","years":[2026,2025]}` (newest first; years with >= 1 row). UI feed
 *   for the year picker (the story lists no endpoint for it: QE proposal, see the report).
 * - `/v1/tax-year/{taxpayer}/{year}` -> 200 JSON `{"taxpayerId","year","currency":"CAD","rows":[{"activityId",
 *   "taxDate":"yyyy-mm-dd","securityId","kind":"DISPOSITION","sequence","sharesUnits","proceedsCents","outlaysCents",
 *   "acbSoldCents","gainCents","superficialLossDeniedCents","notes":[..],"ruleIds":[..],"entryIds":[..],
 *   "substituteEntryIds":[..]}],"totals":{"gainsCents","lossesCents","netCents","taxableCapitalGainCents",
 *   "superficialLossesDeniedCents"},"warnings":[{"ruleId","text","entryIds":[..],"amountMinor"}]}`. All numbers are
 *   JSON integers. Rows, totals and warnings are always present (empty list or 0).
 * - `/v1/tax-year/{taxpayer}/{year}.csv` -> `Content-Type: text/csv`, RFC 4180 (CRLF or LF). First record is the
 *   header `taxDate,security,shares,proceeds,acbSold,outlays,gain,superficialLossDenied,notes`; then one record per JSON
 *   row in the same order (`security` = securityId, `shares` = sharesUnits, `notes` joined with `; `); then ONE totals
 *   record whose `gain` cell is the net and whose `superficialLossDenied` cell is the total denied (other cells free).
 *   Money cells are integer cents or locale-free two-decimal dollars (owner decision, not tested). A `security` or
 *   `notes` cell that starts with `=`, `+`, `-` or `@` is prefixed with `'`.
 * - 404: unknown taxpayer (the activity source returns an empty list), body contains the taxpayer key.
 * - 400: `year` is not 1..9999 digits (`20x5`, `0`, `10000`), body contains the word `year`.
 * - A taxpayer with activities and no sale in the year: 200, `rows` empty, totals all 0.
 *
 * UI contract (source-text guards in `L18UiGuardSpec`; `ui/` has no component-test tool): `ui/src` fetches
 * `/api/v1/tax-year/${encodeURIComponent(..)}` (years) and `.../${year}`; a "Your year" tab; a `<select ... onChange`
 * year picker; the card labels "Total gains", "Total losses", "Net", "Taxable capital gain", "Superficial losses
 * denied"; a "Download CSV" link to the `.csv` route; a "Not included" panel; every money cell through `formatMoney(`.
 */
object L18 {
    const val SHARE = Activities.SHARE
    val tp: String = Activities.TAXPAYER
    val CSV_HEADER =
        "taxDate,security,shares,proceeds,acbSold,outlays,gain,superficialLossDenied,notes".split(",")

    data class Resp(val status: HttpStatusCode, val body: String, val contentType: ContentType?)

    /** Runs [requests] against one app whose activity source returns [rows] for [known] only. Writes use [HttpMethod]. */
    suspend fun fetchAll(
        rows: List<PostedActivity>,
        requests: List<Pair<HttpMethod, String>>,
        known: String = tp,
    ): List<Resp> {
        val out = mutableListOf<Resp>()
        testApplication {
            application { taxYearFromActivities { t -> if (t == known) rows else emptyList() } }
            requests.forEach { (method, path) ->
                val res = client.request(path) { this.method = method }
                out += Resp(res.status, res.bodyAsText(), res.contentType())
            }
        }
        return out
    }

    suspend fun fetch(
        rows: List<PostedActivity>,
        path: String,
        known: String = tp,
    ): Resp = fetchAll(rows, listOf(HttpMethod.Get to path), known).single()

    suspend fun year(
        rows: List<PostedActivity>,
        year: Int,
        taxpayer: String = tp,
    ): JsonObject {
        val res = fetch(rows, "/v1/tax-year/$taxpayer/$year", taxpayer)
        check(res.status == HttpStatusCode.OK) { "GET $year gave ${res.status}: ${res.body}" }
        return L17.parse(res.body)
    }

    fun JsonObject.rowList(): List<JsonObject> = getValue("rows").jsonArray.map { it.jsonObject }

    fun JsonObject.warningList(): List<JsonObject> = getValue("warnings").jsonArray.map { it.jsonObject }

    fun JsonObject.totals(): JsonObject = getValue("totals").jsonObject

    /** Every numeric field of [rows] and `totals` is a JSON integer (no decimal point, no exponent, not a string). */
    fun JsonObject.allNumbersAreIntegers(): Boolean =
        (rowList() + totals()).all { o ->
            o.values.filterIsInstance<JsonPrimitive>().filter { !it.isString }.all {
                it.content.matches(
                    Regex("-?\\d+"),
                )
            }
        }

    fun buy(
        key: String,
        security: String,
        date: LocalDate,
        sharesUnits: Long,
        grossCents: Long,
        entry: Long,
        seq: Long = entry,
        commission: Long = 0,
    ): PostedActivity =
        L06Activities.posted(
            Activities.activity(
                key = key,
                type = ActivityType.BUY,
                date = date,
                amountMinor = grossCents,
                securityId = security,
                quantity = sharesUnits,
                commissionMinor = commission,
                sourceSeq = seq,
            ),
            entry,
        )

    fun sell(
        key: String,
        security: String,
        date: LocalDate,
        sharesUnits: Long,
        grossCents: Long,
        entry: Long,
        seq: Long = entry,
        commission: Long = 0,
        settlement: LocalDate? = null,
    ): PostedActivity {
        val a = buy(key, security, date, sharesUnits, grossCents, entry, seq, commission).activity
        return L06Activities.posted(a.copy(activityType = ActivityType.SELL, settlementDate = settlement), entry)
    }

    fun transferIn(
        key: String,
        security: String,
        date: LocalDate,
        sharesUnits: Long,
        entry: Long,
    ): PostedActivity {
        val a = buy(key, security, date, sharesUnits, 0L, entry).activity
        return L06Activities.posted(a.copy(activityType = ActivityType.TRANSFER_IN), entry)
    }

    /**
     * A seeded mixed book over 2024..2026 and two securities: buys, sells (some settling later, some larger than the
     * shares held so they are rejected) and fractional shares. Entry ids are unique; `sourceSeq` is the position.
     */
    fun randomBook(seed: Int): List<PostedActivity> {
        val rnd = Random(seed)
        return (1..16).map { i ->
            val date = LocalDate.of(2024, 1, 1).plusDays(rnd.nextLong(0, 900))
            val security = if (rnd.nextBoolean()) "SYN-A" else "SYN-B"
            val units = rnd.nextLong(1, 20) * SHARE + rnd.nextLong(0, 100) * 10_000
            val gross = rnd.nextLong(1_000, 90_000)
            val commission = rnd.nextLong(0, 500)
            val entry = 5_000L + i
            if (i <= 5 || rnd.nextInt(3) == 0) {
                buy("rb$seed-$i", security, date, units, gross, entry, i.toLong(), commission)
            } else {
                val settle = if (rnd.nextBoolean()) date.plusDays(rnd.nextLong(1, 4)) else null
                sell("rb$seed-$i", security, date, units, gross, entry, i.toLong(), commission, settle)
            }
        }
    }

    /** The cents in a CSV money cell: integer cents (`244000`) or two-decimal dollars (`2440.00`, `-1.50`). */
    fun centsOf(cell: String): Long =
        when {
            cell.matches(Regex("-?\\d+")) -> cell.toLong()
            cell.matches(Regex("-?\\d+\\.\\d{2}")) -> cell.replace(".", "").toLong()
            else -> error("not a money cell: '$cell'")
        }

    /** RFC 4180 records (CRLF or LF; quoted fields may hold commas, quotes as "" and line breaks). */
    fun parseCsv(text: String): List<List<String>> {
        val records = mutableListOf<List<String>>()
        var record = mutableListOf<String>()
        val cell = StringBuilder()
        var quoted = false
        var i = 0
        while (i < text.length) {
            val c = text[i]
            when {
                quoted && c == '"' && text.getOrNull(i + 1) == '"' -> {
                    cell.append('"')
                    i++
                }
                c == '"' -> quoted = !quoted
                !quoted && c == ',' -> {
                    record += cell.toString()
                    cell.clear()
                }
                !quoted && (c == '\n' || c == '\r') -> {
                    if (c == '\r' && text.getOrNull(i + 1) == '\n') i++
                    record += cell.toString()
                    cell.clear()
                    records += record
                    record = mutableListOf()
                }
                else -> cell.append(c)
            }
            i++
        }
        if (cell.isNotEmpty() || record.isNotEmpty()) records += (record + cell.toString())
        return records
    }

    /** A file or directory in the repo, found by walking up from the working directory. */
    fun repoFile(rel: String): File {
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        while (dir != null && !File(dir, rel).exists()) dir = dir.parentFile
        return File(requireNotNull(dir) { "repo root not found for $rel" }, rel)
    }
}
