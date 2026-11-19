package dev.ledgerline.ingest

import dev.ledgerline.core.AccountType
import dev.ledgerline.core.Activity
import dev.ledgerline.core.ActivityType
import dev.ledgerline.core.Currency
import dev.ledgerline.core.Scale
import java.time.LocalDate
import java.util.SplittableRandom

/** Seeded synthetic activity generator. A pure function of its arguments: no clock, locale or time zone. */
object SyntheticGenerator {
    private val securities = listOf("SYN-AAA", "SYN-BBB", "SYN-CCC")
    private const val EVENTS_PER_TAXPAYER = 40

    // Guarantees every activity type appears for each taxpayer, in an order that never oversells.
    private val script =
        listOf(
            ActivityType.DEPOSIT, ActivityType.BUY, ActivityType.TRANSFER_IN, ActivityType.SELL,
            ActivityType.DIVIDEND, ActivityType.RETURN_OF_CAPITAL, ActivityType.FEE,
            ActivityType.FX_CONVERSION, ActivityType.WITHDRAWAL,
        )
    private val randomTypes = ActivityType.entries

    fun generate(
        seed: Long,
        taxpayers: Int,
        year: Int,
    ): String {
        val rnd = SplittableRandom(seed)
        val all = mutableListOf<Activity>()
        for (t in 1..taxpayers) all += taxpayer(rnd, t, year)
        return CanonicalCsv.serialize(all)
    }

    private fun taxpayer(
        rnd: SplittableRandom,
        n: Int,
        year: Int,
    ): List<Activity> {
        val id = "TP-" + n.toString().padStart(4, '0')
        val accountType = AccountType.entries[(n - 1) % AccountType.entries.size]
        val account = "$id-" + listOf("NR1", "TFSA1", "RRSP1")[accountType.ordinal]
        val days = List(EVENTS_PER_TAXPAYER) { rnd.nextInt(365) }.sorted()
        val held = mutableMapOf<String, Long>()
        val out = mutableListOf<Activity>()
        for ((i, day) in days.withIndex()) {
            val effective = LocalDate.ofYearDay(year, day + 1)
            var type = if (i < script.size) script[i] else randomTypes[rnd.nextInt(randomTypes.size)]
            val security = securities[rnd.nextInt(securities.size)]
            if (type == ActivityType.SELL && (held[security] ?: 0L) <= 0L) type = ActivityType.BUY
            val currency = if (rnd.nextInt(4) == 0) Currency.USD else Currency.CAD
            val settlement = if (rnd.nextInt(5) == 0) null else effective.plusDays(rnd.nextInt(3).toLong())
            val key = "$id-$year-" + (i + 1).toString().padStart(4, '0')
            val priceCents = 500L + rnd.nextInt(20000)
            val unit = unitsPerShare()
            var qty: Long? = null
            var amount: Long
            when (type) {
                ActivityType.BUY, ActivityType.TRANSFER_IN -> {
                    qty = (1L + rnd.nextInt(200)) * unit + rnd.nextInt(100) * (unit / 100)
                    held.merge(security, qty, Long::plus)
                    amount = qty * priceCents / unit
                }
                ActivityType.SELL -> {
                    val have = held.getValue(security)
                    qty = if (have >= unit) (1L + rnd.nextLong(have / unit)) * unit else have
                    held[security] = have - qty
                    amount = qty * priceCents / unit
                }
                ActivityType.DEPOSIT, ActivityType.WITHDRAWAL -> amount = 100_00L + rnd.nextInt(500_000)
                ActivityType.DIVIDEND, ActivityType.RETURN_OF_CAPITAL -> amount = 1_00L + rnd.nextInt(50_000)
                ActivityType.FEE -> amount = 1_00L + rnd.nextInt(5_000)
                ActivityType.FX_CONVERSION -> amount = 100_00L + rnd.nextInt(500_000)
            }
            val usesSecurity =
                type in
                    setOf(
                        ActivityType.BUY, ActivityType.SELL, ActivityType.DIVIDEND, ActivityType.RETURN_OF_CAPITAL,
                        ActivityType.TRANSFER_IN,
                    )
            val commission = if (type == ActivityType.BUY || type == ActivityType.SELL) 495L else 0L
            val fx = type == ActivityType.FX_CONVERSION
            out +=
                Activity(
                    key, id, account, accountType, type, effective, settlement,
                    if (usesSecurity) security else null, qty, currency, amount, commission,
                    if (fx) (if (currency == Currency.CAD) Currency.USD else Currency.CAD) else null,
                    if (fx) amount * 72 / 100 + 1 else null,
                    if (type == ActivityType.DEPOSIT && i == 0) "Initial deposit" else null,
                )
        }
        return out
    }

    private fun unitsPerShare(): Long {
        var r = 1L
        repeat(Scale.SHARE_DECIMALS) { r *= 10 }
        return r
    }
}
