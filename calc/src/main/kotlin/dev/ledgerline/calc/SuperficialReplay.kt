package dev.ledgerline.calc

import dev.ledgerline.core.AccountType
import dev.ledgerline.core.ActivityType
import java.math.BigInteger
import java.time.LocalDate

/** An acquisition that may be substituted property; [index] is its place in canonical order. */
private class Acquisition(val index: Int, val posted: PostedActivity) {
    val quantity: Long = posted.activity.quantity ?: 0L
}

private class AddBack(val key: PoolKey, val sale: PostedActivity, val amount: Long, val target: PostedActivity)

/**
 * Replay with the superficial loss rule. Add-backs depend on later acquisitions, so share counts come from a first
 * pass (add-backs never change shares) and each add-back is applied when the replay reaches its target activity.
 */
internal fun replaySuperficialLosses(
    engine: AcbEngine,
    activities: Collection<PostedActivity>,
): SlReplay {
    val ordered =
        canonicalOrder(engine, activities).filter {
            it.activity.activityType == ActivityType.BUY ||
                it.activity.activityType == ActivityType.SELL ||
                (it.activity.accountType == AccountType.NON_REGISTERED && it.activity.activityType in POOL_TYPES)
        }
    val rejectedIds = plainRejections(engine, ordered)
    val matcher = SuperficialMatcher(engine.ruleSet, ordered, rejectedIds)

    val pools = mutableMapOf<PoolKey, AcbPool>()
    val sales = mutableListOf<SaleResult>()
    val rejections = mutableListOf<Rejection>()
    val pending = mutableMapOf<Int, MutableList<AddBack>>()
    ordered.forEachIndexed { i, posted ->
        if (posted.activity.accountType == AccountType.NON_REGISTERED) {
            val key = PoolKey(posted.activity.taxpayerId, requireNotNull(posted.activity.securityId))
            val before = pools[key] ?: AcbPool.empty(key.taxpayerId, key.securityId)
            when (val step = engine.apply(before, posted)) {
                is PoolStep.Rejected -> {
                    pools[key] = step.pool
                    rejections += step.rejection
                }
                is PoolStep.Applied -> {
                    pools[key] = step.pool
                    step.gains.firstOrNull { it.kind == GainKind.DISPOSITION }?.let { line ->
                        val m = matcher.assess(i, posted, line)
                        sales += m.result
                        m.addBack?.let { (at, amount, target) ->
                            pending.getOrPut(at) { mutableListOf() } += AddBack(key, posted, amount, target)
                        }
                    }
                }
            }
        }
        pending.remove(i)?.forEach { ab ->
            val pool = pools.getValue(ab.key)
            val change =
                PoolChange(
                    ab.sale.activityId,
                    ab.sale.entryId,
                    engine.ruleSet.taxDate(ab.target.activity),
                    0L,
                    ab.amount,
                    setOf("SL-ADDBACK-TARGET", "SL-ADDBACK-TIMING"),
                )
            pools[ab.key] =
                pool.copy(totalAcb = Math.addExact(pool.totalAcb, ab.amount), history = pool.history + change)
        }
    }
    val sorted = pools.toSortedMap(compareBy({ it.taxpayerId }, { it.securityId }))
    return SlReplay(engine.ruleSet.id, sorted, sales, rejections, slHash(engine.ruleSet.id, sorted, sales))
}

private val POOL_TYPES = setOf(ActivityType.BUY, ActivityType.SELL, ActivityType.RETURN_OF_CAPITAL)

private fun plainRejections(
    engine: AcbEngine,
    ordered: List<PostedActivity>,
): Set<String> {
    val pools = mutableMapOf<PoolKey, AcbPool>()
    val rejected = mutableSetOf<String>()
    for (posted in ordered.filter { it.activity.accountType == AccountType.NON_REGISTERED }) {
        val key = PoolKey(posted.activity.taxpayerId, requireNotNull(posted.activity.securityId))
        val step = engine.apply(pools[key] ?: AcbPool.empty(key.taxpayerId, key.securityId), posted)
        pools[key] = step.pool
        if (step is PoolStep.Rejected) rejected += posted.activityId
    }
    return rejected
}

private fun slHash(
    ruleSetId: String,
    pools: Map<PoolKey, AcbPool>,
    sales: List<SaleResult>,
): String =
    sha256Hex(
        buildString {
            append("rules=").append(ruleSetId).append('\n')
            appendPools(pools)
            sales.forEach { s ->
                append(
                    "sale|${s.activityId}|${s.taxDate}|${s.gainBeforeRule}|${s.denied}|${s.reportedGain}|${s.outcome}|",
                )
                append(s.triggers.joinToString(",") { "${it.activityId}:${it.accountType}:${it.shares}" }).append('|')
                append(s.ruleIds.sorted().joinToString(",")).append('\n')
            }
        },
    )

internal class Assessment(val result: SaleResult, val addBack: Triple<Int, Long, PostedActivity>?)

/** Matches loss sales to substituted acquisitions. Each acquired share is matched to one sale, earliest sale first. */
internal class SuperficialMatcher(
    private val rules: TaxRuleSet,
    ordered: List<PostedActivity>,
    rejectedIds: Set<String>,
) {
    private val accepted = ordered.withIndex().filter { it.value.activityId !in rejectedIds }
    private val consumed = mutableMapOf<String, Long>()

    fun assess(
        saleIndex: Int,
        sale: PostedActivity,
        line: GainLine,
    ): Assessment {
        val a = sale.activity
        val sold = a.quantity ?: 0L
        val window = rules.superficialWindow(line.taxDate)
        val sameSecurity =
            accepted.filter {
                it.value.activity.let {
                        o ->
                    o.taxpayerId == a.taxpayerId && o.securityId == a.securityId
                }
            }
        val candidates =
            sameSecurity
                .filter { it.index != saleIndex && it.value.activity.activityType == ActivityType.BUY }
                .filter { rules.taxDate(it.value.activity) in window }
                .map { Acquisition(it.index, it.value) }
        val available = { c: Acquisition -> c.quantity - consumed.getOrDefault(c.posted.activityId, 0L) }
        val acquired = candidates.sumOf(available)
        val held = heldAtEnd(sameSecurity.map { it.value }, window)
        val loss = -line.gain
        val denied = if (line.gain < 0L) rules.superficialLossDenied(sold, acquired, held, loss) else 0L
        if (denied == 0L) {
            return Assessment(
                result(sale, line, 0L, SlOutcome.NONE, emptyList(), noDenialText(line)),
                null,
            )
        }

        var remaining = minOf(sold, acquired, held)
        val matched = remaining
        val triggers = mutableListOf<SlTrigger>()
        for (c in candidates) {
            val take = minOf(available(c), remaining)
            if (take <= 0L) continue
            remaining -= take
            consumed.merge(c.posted.activityId, take, Math::addExact)
            triggers += SlTrigger(c.posted.activityId, c.posted.activity.accountType, take)
        }
        val nonRegistered = triggers.filter { it.accountType == AccountType.NON_REGISTERED }.sumOf { it.shares }
        val added = (big(denied) * big(nonRegistered) / big(matched)).longValueExact()
        val outcome = if (added > 0L) SlOutcome.ADDED_TO_ACB else SlOutcome.PERMANENTLY_LOST
        val byId = candidates.associateBy { it.posted.activityId }
        val targets =
            triggers.filter { it.accountType == AccountType.NON_REGISTERED }.map { byId.getValue(it.activityId) }
        val target = targets.firstOrNull { rules.taxDate(it.posted.activity) >= line.taxDate } ?: targets.firstOrNull()
        val addBack =
            if (added > 0L && target != null) {
                Triple(
                    maxOf(saleIndex, target.index),
                    added,
                    target.posted,
                )
            } else {
                null
            }
        return Assessment(
            result(sale, line, denied, outcome, triggers, denialText(sale, denied, added, outcome, triggers)),
            addBack,
        )
    }

    /** `SL-HELD-END`: non-registered shares held at the end, plus registered in-window buys still held. */
    private fun heldAtEnd(
        all: List<PostedActivity>,
        window: ClosedRange<LocalDate>,
    ): Long {
        fun net(list: List<PostedActivity>): Long =
            list.filter { rules.taxDate(it.activity) <= window.endInclusive }.sumOf {
                val q = it.activity.quantity ?: 0L
                if (it.activity.activityType == ActivityType.BUY) q else -q
            }
        val trades = all.filter { it.activity.activityType != ActivityType.RETURN_OF_CAPITAL }
        val nonRegistered = net(trades.filter { it.activity.accountType == AccountType.NON_REGISTERED })
        val registered =
            trades.filter { it.activity.accountType != AccountType.NON_REGISTERED }.groupBy { it.activity.accountId }
                .values.sumOf { account ->
                    val acquired =
                        account.filter { it.activity.activityType == ActivityType.BUY && inWindow(it, window) }
                            .sumOf { it.activity.quantity ?: 0L }
                    minOf(acquired, maxOf(net(account), 0L))
                }
        return Math.addExact(maxOf(nonRegistered, 0L), registered)
    }

    private fun inWindow(
        posted: PostedActivity,
        window: ClosedRange<LocalDate>,
    ): Boolean = rules.taxDate(posted.activity) in window

    private fun big(x: Long): BigInteger = BigInteger.valueOf(x)

    private fun result(
        sale: PostedActivity,
        line: GainLine,
        denied: Long,
        outcome: SlOutcome,
        triggers: List<SlTrigger>,
        reason: String,
    ): SaleResult {
        val ids = mutableSetOf("SL-DEFINITION", "SL-WINDOW", "SL-IDENTICAL", "SL-HELD-END")
        if (denied > 0L) {
            ids += "SL-PARTIAL"
            if (triggers.any { it.accountType != AccountType.NON_REGISTERED }) ids += "SL-REGISTERED"
            if (outcome == SlOutcome.ADDED_TO_ACB) ids += setOf("SL-ADDBACK-TARGET", "SL-ADDBACK-TIMING")
        }
        return SaleResult(
            sale.activityId, line.taxDate, line.gain, denied, line.gain + denied, outcome, triggers, reason, ids,
        )
    }

    private fun noDenialText(line: GainLine): String =
        if (line.gain >= 0L) "No superficial loss: a gain is never flagged (SL-DEFINITION)" else "No superficial loss"

    private fun denialText(
        sale: PostedActivity,
        denied: Long,
        added: Long,
        outcome: SlOutcome,
        triggers: List<SlTrigger>,
    ): String {
        val names = triggers.joinToString(", ") { "${it.activityId} (${it.accountType}, ${it.shares})" }
        val lost = denied - added
        return "Superficial loss on ${sale.activityId}: $denied denied by $names; outcome $outcome " +
            "(added to ACB $added, permanently lost $lost)"
    }
}
