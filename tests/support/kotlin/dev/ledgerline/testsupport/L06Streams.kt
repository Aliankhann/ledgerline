package dev.ledgerline.testsupport

import dev.ledgerline.calc.PoolKey
import dev.ledgerline.calc.PostedActivity
import dev.ledgerline.core.AccountType
import dev.ledgerline.core.ActivityType
import io.kotest.property.Arb
import io.kotest.property.arbitrary.arbitrary
import java.time.LocalDate
import kotlin.random.Random

/**
 * Generated L-06 activity streams: BUY, SELL and RETURN_OF_CAPITAL for two taxpayers, two securities and, per
 * taxpayer, two non-registered accounts and one TFSA. Values are synthetic; no expected tax value comes from here.
 *
 * A stream is returned **in canonical order** (tax date, then `sourceSeq`, then idempotency key; no settlement dates,
 * so the tax date is the effective date): dates never decrease, keys are zero-padded indices, and `sourceSeq` is the
 * index, or 0 for every activity in about half the streams so that the key alone breaks same-date ties (C4).
 * Most sells fit the shares the pool holds at that point; about [overSellPercent]% ask for more (invariant 7).
 * With [big], about a third of amounts reach 10^9 cents and quantities 10^6 shares, so ACB x shares sold overflows a
 * Long (invariant 8: the engine must still be exact).
 */
object L06Streams {
    private val taxpayers = listOf("TP-0001", "TP-0002")
    val securities = listOf("SYN-A", "SYN-B")

    data class Account(val taxpayerId: String, val accountId: String, val type: AccountType)

    val accounts: List<Account> =
        taxpayers.flatMap { tp ->
            listOf(
                Account(tp, "$tp-NR1", AccountType.NON_REGISTERED),
                Account(tp, "$tp-NR2", AccountType.NON_REGISTERED),
                Account(tp, "$tp-TFSA1", AccountType.TFSA),
            )
        }

    private val start: LocalDate = LocalDate.of(2025, 1, 1)

    fun key(p: PostedActivity): PoolKey = PoolKey(p.activity.taxpayerId, requireNotNull(p.activity.securityId))

    fun isRegistered(p: PostedActivity): Boolean = p.activity.accountType != AccountType.NON_REGISTERED

    private fun cents(
        r: Random,
        big: Boolean,
    ): Long = if (big && r.nextInt(3) == 0) r.nextLong(1, 1_000_000_001L) else r.nextLong(1, 10_000_001L)

    private fun shares(
        r: Random,
        big: Boolean,
    ): Long =
        if (big && r.nextInt(3) == 0) {
            r.nextLong(1, 1_000_000L * Activities.SHARE + 1)
        } else {
            r.nextLong(1, 1_000L * Activities.SHARE + 1)
        }

    fun stream(
        maxLength: Int = 30,
        big: Boolean = false,
        overSellPercent: Int = 10,
    ): Arb<List<PostedActivity>> =
        arbitrary { rs ->
            val r = rs.random
            val n = r.nextInt(1, maxLength + 1)
            val zeroSeq = r.nextBoolean()
            val held = mutableMapOf<PoolKey, Long>()
            var day = 0L
            (0 until n).map { i ->
                day += r.nextInt(0, 4)
                val acct = accounts[r.nextInt(accounts.size)]
                val sec = securities[r.nextInt(securities.size)]
                val pk = PoolKey(acct.taxpayerId, sec)
                val h = held[pk] ?: 0L
                val nonReg = acct.type == AccountType.NON_REGISTERED
                val roll = r.nextInt(100)
                val type =
                    when {
                        roll < 45 -> ActivityType.BUY
                        roll < 80 -> ActivityType.SELL
                        else -> ActivityType.RETURN_OF_CAPITAL
                    }
                val quantity =
                    when (type) {
                        ActivityType.BUY -> shares(r, big).also { if (nonReg) held[pk] = h + it }
                        ActivityType.SELL ->
                            if (!nonReg) {
                                shares(r, big)
                            } else if (h == 0L || r.nextInt(100) < overSellPercent) {
                                h + r.nextLong(1, 10 * Activities.SHARE)
                            } else {
                                r.nextLong(1, h + 1).also { held[pk] = h - it }
                            }
                        else -> null
                    }
                val k = "L06G-%05d".format(i)
                val activity =
                    Activities.activity(
                        key = k,
                        type = type,
                        date = start.plusDays(day),
                        amountMinor = cents(r, big),
                        securityId = sec,
                        quantity = quantity,
                        commissionMinor = if (type == ActivityType.RETURN_OF_CAPITAL) 0L else r.nextLong(0, 10_001L),
                        accountId = acct.accountId,
                        accountType = acct.type,
                        taxpayerId = acct.taxpayerId,
                        sourceSeq = if (zeroSeq) 0L else i.toLong(),
                    )
                PostedActivity(activityId = "act-$k", entryId = 50_000L + i, activity = activity)
            }
        }
}
