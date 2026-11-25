package dev.ledgerline.testsupport

import dev.ledgerline.calc.CaIta2026v1
import dev.ledgerline.calc.PostedActivity
import dev.ledgerline.calc.fx.FxRate
import dev.ledgerline.calc.fx.FxRateCache
import dev.ledgerline.core.Currency
import java.time.LocalDate

/**
 * L-25 USD to CAD conversion (docs/stories/L-25-fx-conversion.md). PROVISIONAL: the story is a Draft.
 *
 * Expected CAD values are arithmetic from the committed Bank of Canada fixture rate through
 * `FxRateCache.toCadCents(CaIta2026v1, usdMinor, rate)`; none is a tax claim (L-08 AC10 stays blocked).
 *
 * API CONTRACT (PROPOSED by QE; Dev may add members, not change these). Every new parameter is optional and defaults
 * to `null`, so every existing call site and test keeps compiling. `fx == null` behaves like an empty cache: a USD
 * activity is then rejected `FX-RATE-MISSING`; a CAD activity never touches the cache (L-08 AC12).
 *
 * ```kotlin
 * // dev.ledgerline.calc
 * class AcbEngine(val ruleSet: TaxRuleSet, val fx: FxRateCache? = null)
 * enum class RejectionCode { INSUFFICIENT_SHARES, FX_RATE_MISSING }   // Rejection.message contains "FX-RATE-MISSING"
 * data class FxStep(
 *     val currency: Currency,         // USD
 *     val originalMinor: Long,        // USD cents: amount + commission for a BUY
 *     val rateScaled: Long,           // FxRate.rateScaled (10^-4)
 *     val rateDate: LocalDate,        // FxRate.date: the published day used (earlier than the trade date on a weekend)
 *     val cadCents: Long,             // toCadCents(rules, originalMinor, rate)
 * )
 * data class ExplainStep(/* existing fields */, val fx: FxStep? = null)   // non-null exactly for converted steps
 * object HoldingExplanation {
 *     fun explain(activities: List<PostedActivity>, bookValueCents: Long?, key: PoolKey, fx: FxRateCache? = null)
 * }
 * object TaxYearReport {
 *     fun build(activities: List<PostedActivity>, taxpayerId: String, year: Int, fx: FxRateCache? = null): TaxYear
 *     fun yearsWithRows(activities: List<PostedActivity>, taxpayerId: String, fx: FxRateCache? = null): List<Int>
 * }
 * // dev.ledgerline.app
 * // a new OVERLOAD (the existing one, used by Main.kt as a function reference, stays as is and means fx = null)
 * fun Application.taxYearFromActivities(fx: FxRateCache?, activities: (taxpayer: String) -> List<PostedActivity>)
 * ```
 *
 * Meaning:
 * - BUY: pool cost = `toCadCents(A + C, rateFor(effectiveDate))`, one rounding, not per part (AC1). SELL: proceeds,
 *   outlays converted separately, each at the sell's own trade date rate; `TaxYearRow` money fields are CAD cents (AC2).
 *   The trade date is `effectiveDate` (not settlement). The rate is `rateFor(date)` (last earlier published, AC3).
 * - `FxRateUnavailableException` for a trade date is an `FX_RATE_MISSING` Rejection (never thrown out of replay, never
 *   guessed). The pool is unchanged by it. The rejection surfaces as a `Reason("FX-RATE-MISSING", text, entryIds =
 *   [the rejected activity's entry id], amountMinor = 0)` in `TaxYear.warnings` and `Explanation.reasons` (Data checks).
 * - `TaxYearRow.currency` is CAD for converted rows; `notes` hold one entry naming `USD`, the original amount in
 *   decimal major units with 2 places (`500.01`) and the rate with 4 places (`1.4489`) plus the rate date.
 *   `FX-NOT-CONVERTED` is never emitted. All converted rows count in `TaxYearTotals`.
 * - `FX-HISTORICAL` (AC6): for a USD key whose book value equals `toCadCents(total USD cost, singleRate)` for one rate
 *   that is not a trade-date rate, reasons hold `Reason("FX-HISTORICAL", text, buyEntryIds, acbCents - bookValueCents)`.
 *   It counts toward `fullyExplained` and `adjustmentCount` like any reason. Total USD cost = sum of USD (amount +
 *   commission) of the buys held. Which single rate (a published day) is Dev's detection; QE tests one fixture day.
 */
object L25 {
    val rules = CaIta2026v1
    const val SHARE = Activities.SHARE
    val tp: String = Activities.TAXPAYER

    fun d(text: String): LocalDate = LocalDate.parse(text)

    fun cache(): FxRateCache = L08Fx.cache()

    fun usd(p: PostedActivity): PostedActivity = p.copy(activity = p.activity.copy(currency = Currency.USD))

    fun usdBuy(
        key: String,
        security: String,
        date: LocalDate,
        shares: Long,
        usdCents: Long,
        entry: Long,
        commission: Long = 0,
    ): PostedActivity = usd(L18.buy(key, security, date, shares, usdCents, entry, commission = commission))

    fun usdSell(
        key: String,
        security: String,
        date: LocalDate,
        shares: Long,
        usdCents: Long,
        entry: Long,
        commission: Long = 0,
    ): PostedActivity = usd(L18.sell(key, security, date, shares, usdCents, entry, commission = commission))

    /** The arithmetic every expectation uses: [usdMinor] at the rate for [date], rounded once. */
    fun cad(
        fx: FxRateCache,
        usdMinor: Long,
        date: LocalDate,
    ): Long = fx.toCadCents(rules, usdMinor, fx.rateFor(date))

    fun cadAt(
        fx: FxRateCache,
        usdMinor: Long,
        rate: FxRate,
    ): Long = fx.toCadCents(rules, usdMinor, rate)
}
