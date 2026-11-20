package dev.ledgerline.calc

import dev.ledgerline.core.Activity
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
}

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
}
