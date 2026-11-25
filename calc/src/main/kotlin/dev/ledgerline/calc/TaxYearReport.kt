package dev.ledgerline.calc

import dev.ledgerline.calc.fx.FxRateCache
import dev.ledgerline.core.Currency
import java.time.LocalDate

/** One sale in the tax year view. Money is whole cents (CAD); [sharesUnits] is 10^-6 share. */
data class TaxYearRow(
    val activityId: String,
    val taxDate: LocalDate,
    val securityId: String,
    val kind: GainKind,
    val sequence: Long,
    val sharesUnits: Long,
    val proceedsCents: Long,
    val outlaysCents: Long,
    val acbSoldCents: Long,
    val gainCents: Long,
    val superficialLossDeniedCents: Long,
    val notes: List<String>,
    val ruleIds: List<String>,
    val entryIds: List<Long>,
    val substituteEntryIds: List<Long>,
    /** Currency of the money fields: CAD once converted (L-25); the activity's own currency only when the caller gave no rate cache. */
    val currency: Currency = Currency.CAD,
)

data class TaxYearTotals(
    val gainsCents: Long,
    val lossesCents: Long,
    val netCents: Long,
    val taxableCapitalGainCents: Long,
    val superficialLossesDeniedCents: Long,
)

data class TaxYear(
    val taxpayerId: String,
    val year: Int,
    val rows: List<TaxYearRow>,
    val totals: TaxYearTotals,
    val warnings: List<Reason>,
)

/**
 * L-18: the realised gains of one tax year, built only from the existing superficial-loss replay. No tax rule is added
 * here; proceeds and the selling commission are joined from the SELL activity by activity id.
 */
object TaxYearReport {
    // OWNER-INPUT: L-18 inclusion rate 1/2 for every year (docs/tax-model.md quotes ITA-38); per-year table is open.
    private const val INCLUSION_NUMERATOR = 1L
    private const val INCLUSION_DENOMINATOR = 2L

    /**
     * Rows for [taxpayerId] whose tax date is in [year]; [activities] may be in any order and hold other taxpayers.
     * `DEEMED_GAIN_ROC` lines are not rows (OWNER-INPUT: L-18 provisional, owner has not decided).
     */
    fun build(
        activities: List<PostedActivity>,
        taxpayerId: String,
        year: Int,
        fx: FxRateCache? = null,
    ): TaxYear {
        val engine = AcbEngine(CaIta2026v1, fx)
        val mine = activities.filter { it.activity.taxpayerId == taxpayerId }
        val rows = allRows(engine, mine).filter { it.taxDate.year == year }
        val counted = rows
        val gains = counted.filter { it.gainCents > 0 }.sumOf { it.gainCents }
        val losses = counted.filter { it.gainCents < 0 }.sumOf { it.gainCents }
        val net = gains + losses
        val totals =
            TaxYearTotals(gains, losses, net, taxableCents(net), counted.sumOf { it.superficialLossDeniedCents })
        val gapWarnings =
            DataGapWarnings.detect(mine, engine.replay(mine)).entries
                .sortedWith(compareBy({ it.key.taxpayerId }, { it.key.securityId }))
                .flatMap { (_, reasons) -> reasons.sortedBy { it.ruleId } }
        return TaxYear(taxpayerId, year, rows, totals, gapWarnings)
    }

    /** Years with at least one row for [taxpayerId], newest first. */
    fun yearsWithRows(
        activities: List<PostedActivity>,
        taxpayerId: String,
        fx: FxRateCache? = null,
    ): List<Int> =
        allRows(AcbEngine(CaIta2026v1, fx), activities.filter { it.activity.taxpayerId == taxpayerId })
            .map { it.taxDate.year }.distinct().sortedDescending()

    /**
     * The taxable part of [netCents] at the 1/2 inclusion rate. A net of 0 or less gives 0.
     * OWNER-INPUT: L-18 odd positive net: provisionally truncates the half cent (round half down). Owner decides.
     */
    fun taxableCents(netCents: Long): Long =
        if (netCents <= 0L) 0L else netCents * INCLUSION_NUMERATOR / INCLUSION_DENOMINATOR

    private fun allRows(
        engine: AcbEngine,
        mine: List<PostedActivity>,
    ): List<TaxYearRow> {
        val replay = engine.replaySuperficial(mine)
        val byActivity = mine.associateBy { it.activityId }
        val entryOf = byActivity.mapValues { it.value.entryId }
        return replay.sales
            .map { sale -> row(engine, sale, byActivity.getValue(sale.activityId), replay, entryOf) }
            .sortedWith(compareBy({ it.taxDate }, { it.sequence }, { it.entryIds.first() }))
    }

    private fun row(
        engine: AcbEngine,
        sale: SaleResult,
        posted: PostedActivity,
        replay: SlReplay,
        entryOf: Map<String, Long>,
    ): TaxYearRow {
        val a = posted.activity
        val rules = engine.ruleSet
        val key = PoolKey(a.taxpayerId, requireNotNull(a.securityId))
        // The engine's own removed ACB for this sale (the add-back entries share the activity id but move no shares).
        val acbSold =
            replay.pools.getValue(key).history
                .first { it.activityId == sale.activityId && it.sharesDelta < 0 }.acbDelta.let { -it }
        val substitutes = sale.triggers.mapNotNull { entryOf[it.activityId] }.sorted()
        // L-25: a USD sale's money is CAD cents at its own trade-date rate; the original USD amount is kept in a note.
        val rate = engine.rateFor(a)
        val fxNotes =
            if (rate == null) {
                emptyList()
            } else {
                listOf(
                    "FX: ${a.currency} ${usd(a.amountMinor)} proceeds and ${usd(a.commissionMinor)} commission " +
                        "converted at ${rate4(rate.rateScaled)} (Bank of Canada rate of ${rate.date})",
                )
            }
        val slNotes =
            if (sale.denied > 0L) {
                listOf(
                    "SL-PARTIAL: loss denied ${rules.toReportedCents(sale.denied)} cents; " +
                        "sale entry ${posted.entryId}; substituted buy entries ${substitutes.joinToString(", ")}; " +
                        "outcome ${sale.outcome}",
                )
            } else {
                emptyList()
            }
        val notes = fxNotes + slNotes
        return TaxYearRow(
            activityId = sale.activityId,
            taxDate = sale.taxDate,
            securityId = key.securityId,
            kind = GainKind.DISPOSITION,
            sequence = a.sourceSeq,
            sharesUnits = a.quantity ?: 0L,
            proceedsCents = engine.cadCents(a.amountMinor, rate),
            outlaysCents = engine.cadCents(a.commissionMinor, rate),
            acbSoldCents = rules.toReportedCents(acbSold),
            gainCents = rules.toReportedCents(sale.reportedGain),
            superficialLossDeniedCents = rules.toReportedCents(sale.denied),
            notes = notes,
            ruleIds = sale.ruleIds.sorted(),
            entryIds = listOf(posted.entryId),
            substituteEntryIds = substitutes,
            currency = if (rate == null) a.currency else Currency.CAD,
        )
    }

    private fun usd(minor: Long): String = "${minor / 100}.${(minor % 100).toString().padStart(2, '0')}"

    private fun rate4(scaled: Long): String = "${scaled / 10_000}.${(scaled % 10_000).toString().padStart(4, '0')}"
}
