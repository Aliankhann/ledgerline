package dev.ledgerline.testsupport

import dev.ledgerline.ledger.LedgerLine
import dev.ledgerline.ledger.Side

/**
 * Test-side oracle for ledger lines, independent of the production code. All sums use exact arithmetic so an
 * overflow fails the test instead of wrapping.
 */
object LineMath {
    /** Unit name for a security holding (owner placeholder: share lines are their own commodity). */
    fun securityUnit(securityId: String): String = "SEC:$securityId"

    data class Totals(val debits: Long, val credits: Long)

    /** Debit and credit totals per unit. */
    fun totalsByUnit(lines: List<LedgerLine>): Map<String, Totals> =
        lines.groupBy { it.unit }.mapValues { (_, ls) ->
            Totals(
                debits = ls.filter { it.side == Side.DEBIT }.fold(0L) { acc, l -> Math.addExact(acc, l.amount) },
                credits = ls.filter { it.side == Side.CREDIT }.fold(0L) { acc, l -> Math.addExact(acc, l.amount) },
            )
        }

    /** Units whose debits differ from credits, mapped to debits minus credits. Empty when balanced. */
    fun imbalances(lines: List<LedgerLine>): Map<String, Long> =
        totalsByUnit(lines).mapValues { (_, t) -> Math.subtractExact(t.debits, t.credits) }.filterValues { it != 0L }

    /** Signed movement of [account] in [unit]: debits add, credits subtract (asset-normal). */
    fun delta(
        lines: List<LedgerLine>,
        account: String,
        unit: String,
    ): Long =
        lines.filter { it.account == account && it.unit == unit }.fold(0L) { acc, l ->
            if (l.side == Side.DEBIT) Math.addExact(acc, l.amount) else Math.subtractExact(acc, l.amount)
        }

    /** Order-insensitive comparison key for a list of lines. */
    fun canonical(lines: List<LedgerLine>): List<String> =
        lines.map { "${it.account}|${it.unit}|${it.side}|${it.amount}" }.sorted()
}
