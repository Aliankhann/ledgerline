package dev.ledgerline.calc.fx

import dev.ledgerline.calc.TaxRuleSet
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.time.LocalDate
import java.util.TreeMap

/** One raw Valet response and the inclusive range it was requested for (the declared coverage, `FX-COVERAGE`). */
class ValetResponse(
    val json: String,
    val start: LocalDate,
    val end: LocalDate,
)

/** The date of the rate used and the rate at 10^-4 (`FX-SCALE`). */
data class FxRate(
    val date: LocalDate,
    val rateScaled: Long,
)

/** No usable rate: the date is outside declared coverage, or no rate exists on or before it in its range. */
class FxRateUnavailableException(
    date: LocalDate,
) : RuntimeException("No $SERIES rate available on or before $date within declared coverage")

private const val SERIES = "FXUSDCAD"

// OWNER-INPUT: FX-SCALE: rates at 10^-4; more than four decimals is rejected.
private const val RATE_DECIMALS = 4
private const val RATE_SCALE = 10_000L

/** In-memory `FXUSDCAD` rates parsed from Valet JSON. No I/O, no floating point. */
class FxRateCache private constructor(
    private val rates: TreeMap<LocalDate, Long>,
    private val coverage: List<ClosedRange<LocalDate>>,
) {
    /** Number of actual rate lookups (CAD skips are not counted). */
    var lookupCount: Int = 0
        private set

    // OWNER-INPUT: FX-FALLBACK / FX-COVERAGE: closest preceding rate day, never leaving a covered range.
    fun rateFor(date: LocalDate): FxRate {
        lookupCount++
        val entry = rates.floorEntry(date)
        if (entry == null || coverage.none { it.start <= entry.key && date <= it.endInclusive }) {
            throw FxRateUnavailableException(date)
        }
        return FxRate(entry.key, entry.value)
    }

    /** Every published rate, oldest first. Not a lookup: it does not count in [lookupCount]. */
    fun publishedRates(): List<FxRate> = rates.map { FxRate(it.key, it.value) }

    /** `FX-CAD-REQUIRED`: null for CAD, no lookup. */
    fun rateFor(
        currency: String,
        date: LocalDate,
    ): FxRate? =
        when (currency) {
            "CAD" -> null
            "USD" -> rateFor(date)
            else -> throw IllegalArgumentException("Unsupported currency $currency")
        }

    /** Exact product: CAD at 10^-4 cent (`FX-SCALE`). Overflow throws [ArithmeticException]. */
    fun toCadInternal(
        amountUsdMinor: Long,
        rate: FxRate,
    ): Long = Math.multiplyExact(amountUsdMinor, rate.rateScaled)

    /** Conversion rounded once, through the rule set's reporting rounding (`ROUND-MODE`). */
    fun toCadCents(
        rules: TaxRuleSet,
        amountUsdMinor: Long,
        rate: FxRate,
    ): Long {
        check(rules.acbUnitsPerCent == RATE_SCALE) { "Rule set scale must match FX-SCALE" }
        return rules.toReportedCents(toCadInternal(amountUsdMinor, rate))
    }

    companion object {
        fun fromValetJson(responses: List<ValetResponse>): FxRateCache {
            val rates = TreeMap<LocalDate, Long>()
            for (r in responses) {
                val observations = Json.parseToJsonElement(r.json).jsonObject["observations"]?.jsonArray ?: continue
                for (o in observations) {
                    val obj: JsonObject = o.jsonObject
                    val date = LocalDate.parse(obj.getValue("d").jsonPrimitive.content)
                    require(date >= r.start && date <= r.end) { "$date outside declared range ${r.start}..${r.end}" }
                    val rate = parseScaled(obj.getValue(SERIES).jsonObject.getValue("v").jsonPrimitive.content)
                    val previous = rates.put(date, rate)
                    require(previous == null || previous == rate) {
                        "Conflicting rates for $date: $previous vs $rate"
                    }
                }
            }
            return FxRateCache(rates, responses.map { it.start..it.end })
        }

        private fun parseScaled(v: String): Long {
            val parts = v.split('.')
            require(parts.size in 1..2 && parts.all { it.isNotEmpty() && it.all(Char::isDigit) }) { "Bad rate '$v'" }
            val fraction = parts.getOrElse(1) { "" }
            require(fraction.length <= RATE_DECIMALS) { "Rate '$v' has more than $RATE_DECIMALS decimals" }
            val whole = Math.multiplyExact(parts[0].toLong(), RATE_SCALE)
            val scaled = Math.addExact(whole, fraction.padEnd(RATE_DECIMALS, '0').toLong())
            require(scaled > 0) { "Rate '$v' must be positive" }
            return scaled
        }
    }
}
