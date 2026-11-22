package dev.ledgerline.testsupport

import dev.ledgerline.core.AccountType
import dev.ledgerline.core.Activity
import dev.ledgerline.core.ActivityType
import dev.ledgerline.core.Currency
import java.time.LocalDate
import java.util.SplittableRandom

/**
 * L-15 generator settings (AC2 "fixed generator settings, recorded in the test"). Seeds 1..200. Scope of the story:
 * CAD, one taxpayer, one account (non-registered), BUY and SELL with commissions. Every history:
 * - starts with one BUY of each of three securities, then 13 to 33 random events;
 * - events are 0 to 9 days apart, so a history spans roughly 100 to 150 days and 61-day windows overlap often;
 * - price moves -25% to +25% per event and is whole cents; shares are whole, or odd halves when the price is even;
 * - a sell is 40% likely to be the whole position, otherwise a random whole number of shares; never more than held;
 * - tax date is the settlement date; 1 in 4 activities has no settlement date (tax date = trade date), the others
 *   settle 1 or 2 days after the trade date (`DATE-BASIS`);
 * - commission is one of 0.00, 0.01, 4.95, 9.95.
 * The production `SyntheticGenerator` is not used: it emits USD, registered accounts and unit prices that are not
 * whole cents, all outside this story's scope.
 */
object L15Histories {
    const val FIRST_SEED = 1L
    const val LAST_SEED = 200L
    const val ACCOUNT = "TP-0001-NR1"
    val securities = listOf("SYN-AAA", "SYN-BBB", "SYN-CCC")

    private const val SHARE = 1_000_000L
    private const val EXTRA_EVENTS_MIN = 13
    private const val EXTRA_EVENTS_MAX = 33
    private const val MAX_GAP_DAYS = 9
    private val firstDay = LocalDate.of(2025, 1, 6)
    private val commissions = listOf(0L, 1L, 495L, 995L)

    val seeds: LongRange get() = FIRST_SEED..LAST_SEED

    /** Activities in generation order, which is canonical order (tax date never decreases; `sourceSeq` = position). */
    fun history(seed: Long): List<Activity> {
        val rnd = SplittableRandom(seed)
        val count = securities.size + EXTRA_EVENTS_MIN + rnd.nextInt(EXTRA_EVENTS_MAX - EXTRA_EVENTS_MIN + 1)
        val price = securities.associateWith { 2000L + 2L * rnd.nextInt(3000) }.toMutableMap()
        val held = securities.associateWith { 0L }.toMutableMap()
        var day = firstDay
        return List(count) { i ->
            day = day.plusDays(rnd.nextInt(MAX_GAP_DAYS + 1).toLong())
            val security = if (i < securities.size) securities[i] else securities[rnd.nextInt(securities.size)]
            var cents = maxOf(100L, price.getValue(security) * (75 + rnd.nextInt(51)) / 100)
            val sell = i >= securities.size && held.getValue(security) > 0L && rnd.nextInt(100) < 45
            val units = if (sell) sellUnits(rnd, held.getValue(security)) else buyUnits(rnd)
            if (units % SHARE != 0L && cents % 2L != 0L) cents += 1L
            price[security] = cents
            held[security] = held.getValue(security) + if (sell) -units else units
            val gross = Math.multiplyExact(units, cents)
            check(gross % SHARE == 0L) { "generator bug: seed=$seed gross is not whole cents" }
            val noSettlement = rnd.nextInt(4) == 0
            Activity(
                idempotencyKey = "TP-0001-2025-" + (i + 1).toString().padStart(4, '0'),
                taxpayerId = Activities.TAXPAYER,
                accountId = ACCOUNT,
                accountType = AccountType.NON_REGISTERED,
                activityType = if (sell) ActivityType.SELL else ActivityType.BUY,
                effectiveDate = if (noSettlement) day else day.minusDays(1L + rnd.nextInt(2)),
                settlementDate = if (noSettlement) null else day,
                securityId = security,
                quantity = units,
                currency = Currency.CAD,
                amountMinor = gross / SHARE,
                commissionMinor = commissions[rnd.nextInt(commissions.size)],
                counterCurrency = null,
                counterAmountMinor = null,
                description = null,
                sourceSeq = (i + 1).toLong(),
            )
        }
    }

    private fun buyUnits(rnd: SplittableRandom): Long =
        if (rnd.nextInt(4) == 0) (2L * rnd.nextInt(100) + 1L) * SHARE / 2L else (1L + rnd.nextInt(100)) * SHARE

    private fun sellUnits(
        rnd: SplittableRandom,
        held: Long,
    ): Long =
        when {
            rnd.nextInt(100) < 40 || held < SHARE -> held
            else -> (1L + rnd.nextLong(held / SHARE)) * SHARE
        }
}
