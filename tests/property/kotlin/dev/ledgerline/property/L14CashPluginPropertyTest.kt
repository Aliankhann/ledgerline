package dev.ledgerline.property

import dev.ledgerline.calc.PluginReplay
import dev.ledgerline.core.Activity
import dev.ledgerline.core.ActivityType
import dev.ledgerline.core.Currency
import dev.ledgerline.plugins.cash.CashBalancePlugin
import dev.ledgerline.plugins.cash.CashState
import dev.ledgerline.testsupport.Activities.activity
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.property.Arb
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.list
import io.kotest.property.arbitrary.long
import io.kotest.property.arbitrary.map
import io.kotest.property.checkAll
import java.time.LocalDate
import kotlin.random.Random

/**
 * Invariants 5, 6 and 8 for the cash-balance plugin (story L-14, provisional). API contract: see the KDoc of
 * `dev.ledgerline.spec.L14CashPluginSpec`. The oracle here is plain addition of the generator's own signed amounts,
 * not the plugin's rules for BUY/SELL/FX, so only DEPOSIT, WITHDRAWAL and FEE are generated.
 */
class L14CashPluginPropertyTest : FunSpec({

    data class Gen(val activity: Activity, val signed: Long)

    val genArb =
        Arb.list(Arb.int(0..2), 0..40).map { kinds ->
            val r = Random(kinds.hashCode().toLong())
            kinds.mapIndexed { i, kind ->
                val amount = r.nextLong(1, 5_000_000_000L) // up to 50 million dollars: tiny to huge
                val type = listOf(ActivityType.DEPOSIT, ActivityType.WITHDRAWAL, ActivityType.FEE)[kind]
                val currency = if (r.nextBoolean()) Currency.CAD else Currency.USD
                val account = "TP-0001-NR${1 + r.nextInt(2)}"
                val date = LocalDate.of(2025, 1, 1).plusDays(r.nextLong(0, 30)) // many same-day ties
                Gen(
                    activity(
                        "K-$i",
                        type,
                        date,
                        amount,
                        currency = currency,
                        accountId = account,
                        sourceSeq = i.toLong(),
                    ),
                    if (type == ActivityType.DEPOSIT) amount else -amount,
                )
            }
        }

    fun balances(state: CashState) =
        listOf("TP-0001-NR1", "TP-0001-NR2").flatMap {
                a ->
            Currency.entries.map { c -> Triple(a, c, state.balanceOf(a, c)) }
        }

    test("L-14: final balance per account and currency equals the sum of signed amounts (300 cases)") {
        checkAll(300, genArb) { gens ->
            val state = PluginReplay.replay(CashBalancePlugin, CashBalancePlugin.empty(), gens.map { it.activity })
            for (a in listOf("TP-0001-NR1", "TP-0001-NR2")) {
                for (c in Currency.entries) {
                    val mine = gens.filter { it.activity.accountId == a && it.activity.currency == c }
                    val expected = mine.sumOf { it.signed }
                    state.balanceOf(a, c) shouldBe expected
                }
            }
        }
    }

    test("L-14: arrival order and redelivery never change the final state or its hash (300 cases)") {
        checkAll(300, genArb, Arb.long()) { gens, seed ->
            val acts = gens.map { it.activity }
            val base = PluginReplay.replay(CashBalancePlugin, CashBalancePlugin.empty(), acts)
            val r = Random(seed)
            val arrival = acts.shuffled(r) + acts.filter { r.nextBoolean() }
            val other = PluginReplay.replay(CashBalancePlugin, CashBalancePlugin.empty(), arrival)
            other shouldBe base
            balances(other) shouldBe balances(base)
            PluginReplay.sha256Hex(other.canonicalText()) shouldBe PluginReplay.sha256Hex(base.canonicalText())
        }
    }

    test("L-14 invariant 8: no Double, Float or BigDecimal in the plugin's state or API") {
        val banned =
            setOf(
                Double::class.javaPrimitiveType,
                Float::class.javaPrimitiveType,
                Double::class.javaObjectType,
                Float::class.javaObjectType,
                java.math.BigDecimal::class.java,
            )
        val classes =
            listOf(CashBalancePlugin.javaClass, CashState::class.java) +
                CashBalancePlugin.explain(CashBalancePlugin.empty()).javaClass
        val types =
            classes.flatMap { c ->
                c.declaredFields.map { it.type } +
                    c.declaredMethods.flatMap {
                            m ->
                        m.parameterTypes.toList() + m.returnType
                    }
            }
        types.filter { it in banned } shouldBe emptyList()
    }
})
