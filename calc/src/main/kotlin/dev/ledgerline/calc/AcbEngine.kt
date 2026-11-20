package dev.ledgerline.calc

import dev.ledgerline.core.AccountType
import dev.ledgerline.core.Activity
import dev.ledgerline.core.ActivityType
import dev.ledgerline.core.Scale
import java.math.BigInteger

/** Pure pooled-ACB engine. Every rule value comes from [ruleSet]; the engine holds none. */
class AcbEngine(val ruleSet: TaxRuleSet) {
    /** The pre-posting gate: a sell larger than the shares held is rejected, never clipped (invariant 7). */
    fun check(
        pool: AcbPool,
        activity: Activity,
    ): Rejection? {
        if (activity.accountType != AccountType.NON_REGISTERED) return null
        if (activity.activityType != ActivityType.SELL) return null
        val requested = activity.quantity ?: 0L
        if (requested <= pool.shares) return null
        return Rejection(
            idempotencyKey = activity.idempotencyKey,
            code = RejectionCode.INSUFFICIENT_SHARES,
            held = pool.shares,
            requested = requested,
            message = "Sell rejected: ${pool.shares} shares held, $requested requested (10^-${Scale.SHARE_DECIMALS})",
        )
    }

    fun apply(
        pool: AcbPool,
        posted: PostedActivity,
    ): PoolStep {
        val a = posted.activity
        if (a.accountType != AccountType.NON_REGISTERED) {
            return unchanged(pool, setOf("POOL-REGISTERED-EXCL"), "Registered account: not in the pool")
        }
        check(pool, a)?.let { return PoolStep.Rejected(pool, it) }
        return when (a.activityType) {
            ActivityType.BUY -> buy(pool, posted)
            ActivityType.SELL -> sell(pool, posted)
            ActivityType.RETURN_OF_CAPITAL -> returnOfCapital(pool, posted)
            else -> unchanged(pool, setOf("DATE-BASIS"), "Activity type does not affect the pool")
        }
    }

    /** Display only: total ACB per whole share, in cents, rounded by `ROUND-MODE`. */
    fun acbPerShareCents(pool: AcbPool): Long {
        if (pool.shares == 0L) return 0L
        val perShare = big(pool.totalAcb) * SHARE_UNIT / big(pool.shares)
        return ruleSet.toReportedCents(perShare.longValueExact())
    }

    fun replay(activities: Collection<PostedActivity>): Replay = replayAll(this, activities)

    private fun buy(
        pool: AcbPool,
        posted: PostedActivity,
    ): PoolStep {
        val a = posted.activity
        val rules = setOf("DATE-BASIS", "POOL-IDENTICAL", "COST-BUY-COMMISSION", "ROUND-SCALE")
        val cost = Math.multiplyExact(Math.addExact(a.amountMinor, a.commissionMinor), ruleSet.acbUnitsPerCent)
        return applied(pool, posted, a.quantity ?: 0L, cost, emptyList(), rules, "Buy added to the pool")
    }

    private fun sell(
        pool: AcbPool,
        posted: PostedActivity,
    ): PoolStep {
        val a = posted.activity
        val sold = a.quantity ?: 0L
        val acbSold = (big(pool.totalAcb) * big(sold) / big(pool.shares)).longValueExact()
        val net = Math.subtractExact(a.amountMinor, a.commissionMinor)
        val gain = Math.subtractExact(Math.multiplyExact(net, ruleSet.acbUnitsPerCent), acbSold)
        val rules = setOf("DATE-BASIS", "POOL-IDENTICAL", "ACB-SOLD", "PROCEEDS-SELL-COMMISSION", "ROUND-SCALE")
        val line =
            GainLine(
                kind = GainKind.DISPOSITION,
                activityId = posted.activityId,
                taxDate = ruleSet.taxDate(a),
                gain = gain,
                gainCents = ruleSet.toReportedCents(gain),
                acbSold = acbSold,
                ruleIds = setOf("ACB-SOLD", "PROCEEDS-SELL-COMMISSION", "ROUND-MODE", "ROUND-BOUNDARY"),
            )
        return applied(pool, posted, -sold, -acbSold, listOf(line), rules, "Sell removed shares at pool average ACB")
    }

    private fun returnOfCapital(
        pool: AcbPool,
        posted: PostedActivity,
    ): PoolStep {
        val a = posted.activity
        val roc = Math.multiplyExact(a.amountMinor, ruleSet.acbUnitsPerCent)
        val absorbed = minOf(roc, pool.totalAcb)
        val excess = roc - absorbed
        var rules = setOf("DATE-BASIS", "ROC-REDUCES-ACB", "ROUND-SCALE")
        var gains = emptyList<GainLine>()
        if (excess > 0L) {
            rules = rules + setOf("ROC-NEG-GAIN", "ROC-NEG-RESET", "ROC-NEG-DATE")
            gains =
                listOf(
                    GainLine(
                        kind = GainKind.DEEMED_GAIN_ROC,
                        activityId = posted.activityId,
                        taxDate = ruleSet.taxDate(a),
                        gain = excess,
                        gainCents = ruleSet.toReportedCents(excess),
                        acbSold = 0L,
                        ruleIds = setOf("ROC-NEG-GAIN", "ROC-NEG-DATE", "ROUND-MODE", "ROUND-BOUNDARY"),
                    ),
                )
        }
        return applied(pool, posted, 0L, -absorbed, gains, rules, "Return of capital reduced ACB")
    }

    private fun applied(
        pool: AcbPool,
        posted: PostedActivity,
        sharesDelta: Long,
        acbDelta: Long,
        gains: List<GainLine>,
        rules: Set<String>,
        text: String,
    ): PoolStep.Applied {
        val history =
            if (sharesDelta == 0L && acbDelta == 0L) {
                pool.history
            } else {
                pool.history +
                    PoolChange(
                        posted.activityId,
                        posted.entryId,
                        ruleSet.taxDate(posted.activity),
                        sharesDelta,
                        acbDelta,
                        rules,
                    )
            }
        val next =
            pool.copy(
                shares = Math.addExact(pool.shares, sharesDelta),
                totalAcb = Math.addExact(pool.totalAcb, acbDelta),
                history = history,
            )
        return PoolStep.Applied(next, gains, Explanation(ruleSet.id, rules, text))
    }

    private fun unchanged(
        pool: AcbPool,
        rules: Set<String>,
        text: String,
    ): PoolStep.Applied = PoolStep.Applied(pool, emptyList(), Explanation(ruleSet.id, rules, text))

    private companion object {
        val SHARE_UNIT: BigInteger = BigInteger.TEN.pow(Scale.SHARE_DECIMALS)
    }

    private fun big(x: Long): BigInteger = BigInteger.valueOf(x)
}
