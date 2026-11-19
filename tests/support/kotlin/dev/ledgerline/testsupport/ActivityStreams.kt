package dev.ledgerline.testsupport

import dev.ledgerline.core.AccountType
import dev.ledgerline.core.Activity
import dev.ledgerline.core.ActivityType
import dev.ledgerline.core.Currency
import io.kotest.property.Arb
import io.kotest.property.arbitrary.arbitrary
import java.time.LocalDate
import kotlin.random.Random

/**
 * Generators for random streams of valid canonical activities (L-03 AC8, AC9).
 *
 * Valid means: all nine activity types, CAD and USD, cash amounts in `1..10_000_000_000` minor units, commission never
 * above the amount, FX counter currency differs from the currency, and no SELL exceeds the shares the account holds
 * at that point in the stream. Effective dates are non-decreasing within 2025; `sourceSeq` is the 1-based position in
 * the stream (OWNER-INPUT C4 placeholder). Quantities use the C3 placeholder scale of 10^-6 share.
 */
object ActivityStreams {
    const val MIN_AMOUNT_MINOR = 1L
    const val MAX_AMOUNT_MINOR = 10_000_000_000L
    const val MAX_QUANTITY = 1_000_000L * Activities.SHARE

    private val accounts = listOf("TP-0001-NR1" to AccountType.NON_REGISTERED, "TP-0001-TFSA" to AccountType.TFSA)
    private val securities = mapOf("SYN-AAA" to Currency.CAD, "SYN-BBB" to Currency.CAD, "SYN-USX" to Currency.USD)
    private val types = ActivityType.entries

    /** A stream of 1..[maxLength] activities whose keys start with a unique prefix per generated case. */
    fun stream(
        maxLength: Int = 20,
        keyPrefix: String = "GEN",
    ): Arb<List<Activity>> {
        var case = 0L
        return arbitrary { rs ->
            case += 1
            generate(rs.random, "$keyPrefix-$case", rs.random.nextInt(1, maxLength + 1))
        }
    }

    fun generate(
        random: Random,
        keyPrefix: String,
        length: Int,
    ): List<Activity> {
        val held = mutableMapOf<Pair<String, String>, Long>()
        var date = LocalDate.of(2025, 1, 2)
        return (1..length).map { i ->
            date = minOf(date.plusDays(random.nextLong(0, 4)), LocalDate.of(2025, 12, 31))
            val (account, accountType) = accounts[random.nextInt(accounts.size)]
            var type = types[random.nextInt(types.size)]
            val heldHere = held.filter { (k, v) -> k.first == account && v > 0 }
            if (type == ActivityType.SELL && heldHere.isEmpty()) type = ActivityType.BUY
            val amount = random.nextLong(MIN_AMOUNT_MINOR, MAX_AMOUNT_MINOR + 1)
            val cashCurrency = if (random.nextBoolean()) Currency.CAD else Currency.USD
            val base =
                Activities.activity(
                    key = "$keyPrefix-${"%05d".format(i)}",
                    type = type,
                    date = date,
                    amountMinor = amount,
                    currency = cashCurrency,
                    accountId = account,
                    accountType = accountType,
                    sourceSeq = i.toLong(),
                )
            when (type) {
                ActivityType.DEPOSIT, ActivityType.WITHDRAWAL, ActivityType.FEE -> base
                ActivityType.DIVIDEND, ActivityType.RETURN_OF_CAPITAL -> {
                    val sec = securities.keys.random(random)
                    base.copy(securityId = sec, currency = securities.getValue(sec))
                }
                ActivityType.BUY, ActivityType.TRANSFER_IN -> {
                    val sec = securities.keys.random(random)
                    val qty = random.nextLong(1, MAX_QUANTITY + 1)
                    held.merge(account to sec, qty, Math::addExact)
                    base.copy(
                        securityId = sec,
                        quantity = qty,
                        currency = securities.getValue(sec),
                        commissionMinor =
                            if (type == ActivityType.BUY) {
                                random.nextLong(
                                    0,
                                    minOf(amount, 10_000L) + 1,
                                )
                            } else {
                                0L
                            },
                    )
                }
                ActivityType.SELL -> {
                    val (key, have) = heldHere.entries.toList()[random.nextInt(heldHere.size)].toPair()
                    val qty = random.nextLong(1, have + 1)
                    held[key] = have - qty
                    base.copy(
                        securityId = key.second,
                        quantity = qty,
                        currency = securities.getValue(key.second),
                        commissionMinor = random.nextLong(0, minOf(amount, 10_000L) + 1),
                    )
                }
                ActivityType.FX_CONVERSION ->
                    base.copy(
                        counterCurrency = if (cashCurrency == Currency.CAD) Currency.USD else Currency.CAD,
                        counterAmountMinor = random.nextLong(MIN_AMOUNT_MINOR, MAX_AMOUNT_MINOR + 1),
                    )
            }
        }
    }
}
