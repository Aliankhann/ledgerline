package dev.ledgerline.calc

import dev.ledgerline.calc.fx.FxRate
import dev.ledgerline.calc.fx.FxRateCache
import dev.ledgerline.calc.fx.FxRateUnavailableException
import dev.ledgerline.core.AccountType
import dev.ledgerline.core.Activity
import dev.ledgerline.core.ActivityType
import dev.ledgerline.core.Currency
import dev.ledgerline.core.Scale
import java.math.BigInteger

/**
 * Pure pooled-ACB engine. Every rule value comes from [ruleSet]; the engine holds none.
 * L-25: a USD activity is converted to CAD cents at the Bank of Canada rate for its trade date from [fx]; with [fx]
 * but no rate for its date it is rejected `FX-RATE-MISSING`. With no [fx] at all nothing converts. A CAD activity never touches [fx].
 */
class AcbEngine(val ruleSet: TaxRuleSet, val fx: FxRateCache? = null) {
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
            taxpayerId = activity.taxpayerId,
            securityId = activity.securityId.orEmpty(),
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
        val converts =
            a.activityType == ActivityType.BUY || a.activityType == ActivityType.SELL ||
                a.activityType == ActivityType.RETURN_OF_CAPITAL
        val rate =
            if (converts) {
                try {
                    rateFor(a)
                } catch (e: FxRateUnavailableException) {
                    return PoolStep.Rejected(pool, fxRejection(a, e))
                }
            } else {
                null
            }
        return when (a.activityType) {
            ActivityType.BUY -> buy(pool, posted, rate)
            ActivityType.SELL -> sell(pool, posted, rate)
            ActivityType.RETURN_OF_CAPITAL -> returnOfCapital(pool, posted, rate)
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

    /** Like [replay], with the superficial loss rule (L-07): registered buys are substituted property. */
    fun replaySuperficial(activities: Collection<PostedActivity>): SlReplay = replaySuperficialLosses(this, activities)

    /**
     * The rate for a USD activity's trade date (`effectiveDate`); null for CAD, which never looks one up.
     * OWNER-INPUT: L-25 with no [fx] at all nothing is converted (pre-L-25 behaviour), because tests written before
     * L-25 (L-16 seed 42 holds USD rows) replay with no cache. The running app always passes the cache.
     */
    internal fun rateFor(a: Activity): FxRate? {
        if (a.currency == Currency.CAD || fx == null) return null
        return fx.rateFor(a.effectiveDate)
    }

    /** [minor] in CAD cents: unchanged for CAD (`rate == null`), else converted with one rounding. */
    internal fun cadCents(
        minor: Long,
        rate: FxRate?,
    ): Long = if (rate == null) minor else requireNotNull(fx).toCadCents(ruleSet, minor, rate)

    private fun fxRejection(
        a: Activity,
        e: FxRateUnavailableException,
    ) = Rejection(
        idempotencyKey = a.idempotencyKey,
        code = RejectionCode.FX_RATE_MISSING,
        held = 0L,
        requested = a.quantity ?: 0L,
        message = "FX-RATE-MISSING: ${e.message}",
        taxpayerId = a.taxpayerId,
        securityId = a.securityId.orEmpty(),
    )

    private fun buy(
        pool: AcbPool,
        posted: PostedActivity,
        rate: FxRate?,
    ): PoolStep {
        val a = posted.activity
        val rules = setOf("DATE-BASIS", "POOL-IDENTICAL", "COST-BUY-COMMISSION", "ROUND-SCALE")
        // One rounding of amount + commission together (L-25 AC1); the commission's share is shown at the same rate.
        val cost =
            Math.multiplyExact(
                cadCents(Math.addExact(a.amountMinor, a.commissionMinor), rate),
                ruleSet.acbUnitsPerCent,
            )
        val amounts =
            mapOf(
                "COST-BUY-COMMISSION" to Math.multiplyExact(cadCents(a.commissionMinor, rate), ruleSet.acbUnitsPerCent),
            )
        return applied(pool, posted, a.quantity ?: 0L, cost, emptyList(), rules, "Buy added to the pool", amounts)
    }

    private fun sell(
        pool: AcbPool,
        posted: PostedActivity,
        rate: FxRate?,
    ): PoolStep {
        val a = posted.activity
        val sold = a.quantity ?: 0L
        if (sold == 0L) return unchanged(pool, setOf("ACB-SOLD"), "Zero-quantity sell does not affect the pool")
        val acbSold = (big(pool.totalAcb) * big(sold) / big(pool.shares)).longValueExact()
        val net = Math.subtractExact(cadCents(a.amountMinor, rate), cadCents(a.commissionMinor, rate))
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
        rate: FxRate?,
    ): PoolStep {
        val a = posted.activity
        val roc = Math.multiplyExact(cadCents(a.amountMinor, rate), ruleSet.acbUnitsPerCent)
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
        ruleAmounts: Map<String, Long> = emptyMap(),
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
                        ruleAmounts,
                    )
            }
        val next =
            pool.copy(
                shares = Math.addExact(pool.shares, sharesDelta),
                totalAcb = Math.addExact(pool.totalAcb, acbDelta),
                history = history,
            )
        return PoolStep.Applied(next, gains, StepExplanation(ruleSet.id, rules, text))
    }

    private fun unchanged(
        pool: AcbPool,
        rules: Set<String>,
        text: String,
    ): PoolStep.Applied = PoolStep.Applied(pool, emptyList(), StepExplanation(ruleSet.id, rules, text))

    private companion object {
        val SHARE_UNIT: BigInteger = BigInteger.TEN.pow(Scale.SHARE_DECIMALS)
    }

    private fun big(x: Long): BigInteger = BigInteger.valueOf(x)
}
