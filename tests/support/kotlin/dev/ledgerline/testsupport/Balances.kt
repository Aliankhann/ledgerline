package dev.ledgerline.testsupport

import dev.ledgerline.core.Activity
import dev.ledgerline.core.ActivityType
import dev.ledgerline.ledger.LedgerLine
import dev.ledgerline.ledger.PostedEntry
import dev.ledgerline.ledger.Posting
import dev.ledgerline.ledger.Side

/** Account balances (debits minus credits) per (account, unit), computed by the test, never by production code. */
object Balances {
    /** Non-zero balances only, so "never had the activity" and "had it and reversed it" compare equal. */
    fun of(lines: Iterable<LedgerLine>): Map<Pair<String, String>, Long> =
        lines
            .groupBy { it.account to it.unit }
            .mapValues { (_, ls) ->
                ls.fold(0L) { acc, l ->
                    if (l.side == Side.DEBIT) Math.addExact(acc, l.amount) else Math.subtractExact(acc, l.amount)
                }
            }
            .filterValues { it != 0L }

    fun ofEntries(entries: Iterable<PostedEntry>): Map<Pair<String, String>, Long> = of(entries.flatMap { it.lines })

    fun ofActivities(activities: Iterable<Activity>): Map<Pair<String, String>, Long> =
        of(activities.flatMap(Posting::linesFor))

    /** One balance, 0 when the account has no lines in [unit]. */
    fun balance(
        balances: Map<Pair<String, String>, Long>,
        account: String,
        unit: String,
    ): Long = balances[account to unit] ?: 0L

    /**
     * Test oracle for invariant 7: true when, walking [activities] in calculation order (effective date, source
     * sequence, key), no account ever holds fewer than 0 units of a security. Only BUY and TRANSFER_IN add shares;
     * only SELL removes them.
     */
    fun holdingsNeverNegative(activities: Collection<Activity>): Boolean {
        val held = mutableMapOf<Pair<String, String>, Long>()
        activities
            .sortedWith(compareBy({ it.effectiveDate }, { it.sourceSeq }, { it.idempotencyKey }))
            .forEach { a ->
                val delta =
                    when (a.activityType) {
                        ActivityType.BUY, ActivityType.TRANSFER_IN -> a.quantity!!
                        ActivityType.SELL -> -a.quantity!!
                        else -> return@forEach
                    }
                val k = a.accountId to a.securityId!!
                val now = (held[k] ?: 0L) + delta
                if (now < 0) return false
                held[k] = now
            }
        return true
    }
}
