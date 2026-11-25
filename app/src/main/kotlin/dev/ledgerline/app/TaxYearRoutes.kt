package dev.ledgerline.app

import dev.ledgerline.calc.PostedActivity
import dev.ledgerline.calc.TaxYear
import dev.ledgerline.calc.TaxYearReport
import dev.ledgerline.calc.TaxYearRow
import dev.ledgerline.calc.fx.FxRateCache
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.response.respondText
import io.ktor.server.routing.RoutingContext
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

private val YEAR_TEXT = Regex("\\d{1,4}")

/**
 * L-18 (read-only): `GET /v1/tax-year/{taxpayer}` lists years with rows; `/{year}` is the JSON table and
 * `/{year}.csv` the same rows as CSV. The figures come from [TaxYearReport] over the stored activities.
 */
fun Application.taxYearFromActivities(activities: (taxpayer: String) -> List<PostedActivity>) =
    taxYearFromActivities(null, activities)

/** L-25: with [fx], USD sales are converted to CAD at their trade-date rate; without it they are rejected. */
fun Application.taxYearFromActivities(
    fx: FxRateCache?,
    activities: (taxpayer: String) -> List<PostedActivity>,
) {
    routing {
        get("/v1/tax-year/{taxpayer}") {
            val taxpayer = call.parameters["taxpayer"].orEmpty()
            val acts = knownActivities(taxpayer, activities) ?: return@get
            val years = guarded { TaxYearReport.yearsWithRows(acts, taxpayer, fx) } ?: return@get
            val body =
                buildJsonObject {
                    put("taxpayerId", taxpayer)
                    put("years", JsonArray(years.map { JsonPrimitive(it) }))
                }
            call.respondText(body.toString(), ContentType.Application.Json)
        }
        get("/v1/tax-year/{taxpayer}/{year}") {
            val taxpayer = call.parameters["taxpayer"].orEmpty()
            val raw = call.parameters["year"].orEmpty()
            val csv = raw.endsWith(".csv")
            val yearText = raw.removeSuffix(".csv")
            if (!YEAR_TEXT.matches(yearText) || yearText.toInt() == 0) {
                call.respondText("year must be a number from 1 to 9999: '$raw'", status = HttpStatusCode.BadRequest)
                return@get
            }
            val acts = knownActivities(taxpayer, activities) ?: return@get
            val report = guarded { TaxYearReport.build(acts, taxpayer, yearText.toInt(), fx) } ?: return@get
            if (csv) {
                call.respondText(csvOf(report), ContentType.Text.CSV)
            } else {
                call.respondText(jsonOf(report).toString(), ContentType.Application.Json)
            }
        }
    }
}

/** An amount too large for the engine's integer range fails loudly in calc; here it becomes a clear 422. */
private suspend fun <T> RoutingContext.guarded(block: () -> T): T? =
    try {
        block()
    } catch (e: ArithmeticException) {
        val body =
            buildJsonObject {
                put("error", "AMOUNT_OUT_OF_RANGE")
                put("message", "An amount is too large to calculate (${e.message}); no tax year can be built.")
            }
        call.respondText(body.toString(), ContentType.Application.Json, HttpStatusCode.UnprocessableEntity)
        null
    }

private suspend fun RoutingContext.knownActivities(
    taxpayer: String,
    source: (String) -> List<PostedActivity>,
): List<PostedActivity>? {
    val acts = source(taxpayer)
    if (acts.isEmpty()) call.respondText("Unknown taxpayer: $taxpayer", status = HttpStatusCode.NotFound)
    return acts.takeIf { it.isNotEmpty() }
}

private fun longs(values: List<Long>): JsonElement = JsonArray(values.map { JsonPrimitive(it) })

private fun strings(values: List<String>): JsonElement = JsonArray(values.map { JsonPrimitive(it) })

private fun jsonOf(r: TaxYear): JsonElement =
    buildJsonObject {
        put("taxpayerId", r.taxpayerId)
        put("year", r.year)
        put("currency", "CAD")
        put("rows", buildJsonArray { r.rows.forEach { add(rowJson(it)) } })
        put(
            "totals",
            buildJsonObject {
                put("gainsCents", r.totals.gainsCents)
                put("lossesCents", r.totals.lossesCents)
                put("netCents", r.totals.netCents)
                put("taxableCapitalGainCents", r.totals.taxableCapitalGainCents)
                put("superficialLossesDeniedCents", r.totals.superficialLossesDeniedCents)
            },
        )
        put(
            "warnings",
            buildJsonArray {
                r.warnings.forEach {
                    add(
                        buildJsonObject {
                            put("ruleId", it.ruleId)
                            put("text", it.text)
                            put("entryIds", longs(it.entryIds))
                            put("amountMinor", it.amountMinor)
                        },
                    )
                }
            },
        )
    }

private fun rowJson(r: TaxYearRow): JsonElement =
    buildJsonObject {
        put("activityId", r.activityId)
        put("currency", r.currency.name)
        put("taxDate", r.taxDate.toString())
        put("securityId", r.securityId)
        put("kind", r.kind.name)
        put("sequence", r.sequence)
        put("sharesUnits", r.sharesUnits)
        put("proceedsCents", r.proceedsCents)
        put("outlaysCents", r.outlaysCents)
        put("acbSoldCents", r.acbSoldCents)
        put("gainCents", r.gainCents)
        put("superficialLossDeniedCents", r.superficialLossDeniedCents)
        put("notes", strings(r.notes))
        put("ruleIds", strings(r.ruleIds))
        put("entryIds", longs(r.entryIds))
        put("substituteEntryIds", longs(r.substituteEntryIds))
    }

private const val CSV_HEADER = "taxDate,security,shares,proceeds,acbSold,outlays,gain,superficialLossDenied,notes"

// OWNER-INPUT: L-18 CSV money format: provisionally integer cents (e.g. 244000), not dollars with two decimals.
private fun csvOf(r: TaxYear): String {
    val lines = mutableListOf(CSV_HEADER)
    r.rows.forEach {
        lines +=
            csvLine(
                it.taxDate.toString(), safe(it.securityId), it.sharesUnits.toString(), it.proceedsCents.toString(),
                it.acbSoldCents.toString(), it.outlaysCents.toString(), it.gainCents.toString(),
                it.superficialLossDeniedCents.toString(), safe(csvNotes(it)),
            )
    }
    val t = r.totals
    lines +=
        csvLine(
            "", "TOTAL", "", "", "", "", t.netCents.toString(), t.superficialLossesDeniedCents.toString(),
            "gains=${t.gainsCents}; losses=${t.lossesCents}; taxableCapitalGain=${t.taxableCapitalGainCents}",
        )
    return lines.joinToString("\r\n", postfix = "\r\n")
}

/** A cell a spreadsheet could run as a formula gets a leading apostrophe. */
private fun safe(cell: String): String = if (cell.firstOrNull() in setOf('=', '+', '-', '@')) "'$cell" else cell

private fun csvNotes(row: TaxYearRow): String = row.notes.joinToString("; ")

private fun csvLine(vararg cells: String): String = cells.joinToString(",") { quote(it) }

private fun quote(cell: String): String =
    if (cell.any { it == ',' || it == '"' || it == '\r' || it == '\n' }) "\"${cell.replace("\"", "\"\"")}\"" else cell
