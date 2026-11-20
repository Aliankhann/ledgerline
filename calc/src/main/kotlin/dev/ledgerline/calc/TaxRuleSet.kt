package dev.ledgerline.calc

import dev.ledgerline.core.Activity
import java.math.BigInteger
import java.time.LocalDate

/**
 * The tax rules the ACB engine reads (ADR 0004). Pure: no I/O, clock or floating point. Every rule value lives in an
 * implementation, never in the engine.
 */
interface TaxRuleSet {
    /** Rule set ID and version; goes into every output hash. */
    val id: String

    /** Every Rule ID this rule set defines (docs/tax-model.md). */
    val ruleIds: Set<String>

    /** `ROUND-SCALE`: internal ACB units in one cent. */
    val acbUnitsPerCent: Long

    /** `ROUND-MODE`: internal units to whole cents; used only at a reporting boundary. */
    fun toReportedCents(internal: Long): Long

    /** `DATE-BASIS`: the date that orders and dates an activity. */
    fun taxDate(activity: Activity): LocalDate

    /** `SL-WINDOW`: the period around a sale's tax date, both ends inclusive; "held at the end" is its end. */
    fun superficialWindow(saleTaxDate: LocalDate): ClosedRange<LocalDate> = noSuperficialRules()

    /**
     * `SL-PARTIAL`: the denied part of [loss] (internal units, > 0) for [sold] shares disposed, [acquired] identical
     * shares acquired in the window and [heldAtEnd] shares held at its end (10^-6 units). Result in internal units.
     */
    fun superficialLossDenied(
        sold: Long,
        acquired: Long,
        heldAtEnd: Long,
        loss: Long,
    ): Long = noSuperficialRules()
}

/** Rule sets that predate L-07 define no SL values; the interface holds none either (ADR 0004). */
private fun TaxRuleSet.noSuperficialRules(): Nothing =
    throw UnsupportedOperationException("Rule set $id defines no superficial loss rules")

/** Rule set `CA-ITA-2026.1` (docs/tax-model.md, PROPOSED: the owner has not accepted these values). */
object CaIta2026v1 : TaxRuleSet {
    override val id: String = "CA-ITA-2026.1"

    override val ruleIds: Set<String> =
        setOf(
            "DATE-BASIS",
            "POOL-IDENTICAL",
            "POOL-REGISTERED-EXCL",
            "ACB-SOLD",
            "COST-BUY-COMMISSION",
            "PROCEEDS-SELL-COMMISSION",
            "ROC-REDUCES-ACB",
            "ROC-DATE",
            "ROC-INPUT",
            "ROC-NEG-GAIN",
            "ROC-NEG-RESET",
            "ROC-NEG-DATE",
            "ROUND-SCALE",
            "ROUND-MODE",
            "ROUND-BOUNDARY",
            "SL-DEFINITION",
            "SL-WINDOW",
            "SL-IDENTICAL",
            "SL-HELD-END",
            "SL-PARTIAL",
            "SL-REGISTERED",
            "SL-ADDBACK-TARGET",
            "SL-ADDBACK-TIMING",
            // OWNER-INPUT: C29 not a tax rule: L-11's honest label for a difference no pool step explains. The L-11
            // provisional tests require every reason ID to be in this set.
            "UNEXPLAINED",
        )

    // OWNER-INPUT: C6/C7 ROUND-SCALE: pool ACB at 10^-4 cent.
    override val acbUnitsPerCent: Long = 10_000L

    // OWNER-INPUT: C6/C7 ROUND-MODE: whole cents, half up, away from zero on exact halves.
    override fun toReportedCents(internal: Long): Long {
        val magnitude = Math.absExact(internal)
        var cents = magnitude / acbUnitsPerCent
        if ((magnitude % acbUnitsPerCent) * 2 >= acbUnitsPerCent) cents += 1
        return if (internal < 0) -cents else cents
    }

    // OWNER-INPUT: C6/C7 DATE-BASIS: settlement date when present, else trade date.
    override fun taxDate(activity: Activity): LocalDate = activity.settlementDate ?: activity.effectiveDate

    // OWNER-INPUT: SL-WINDOW: 30 days before through 30 days after the sale's tax date, both ends inclusive.
    override fun superficialWindow(saleTaxDate: LocalDate): ClosedRange<LocalDate> =
        saleTaxDate.minusDays(30)..saleTaxDate.plusDays(30)

    // OWNER-INPUT: SL-PARTIAL (provisional): least(S, P, B) / S x L, rounded down at the internal scale.
    override fun superficialLossDenied(
        sold: Long,
        acquired: Long,
        heldAtEnd: Long,
        loss: Long,
    ): Long {
        val matched = minOf(sold, acquired, heldAtEnd)
        if (sold <= 0L || matched <= 0L || loss <= 0L) return 0L
        return (BigInteger.valueOf(loss) * BigInteger.valueOf(matched) / BigInteger.valueOf(sold)).longValueExact()
    }
}
