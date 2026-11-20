package dev.ledgerline.spec

import dev.ledgerline.calc.AcbEngine
import dev.ledgerline.calc.AcbPool
import dev.ledgerline.calc.CaIta2026v1
import dev.ledgerline.calc.GainKind
import dev.ledgerline.calc.PoolKey
import dev.ledgerline.calc.PoolStep
import dev.ledgerline.calc.PostedActivity
import dev.ledgerline.calc.RejectionCode
import dev.ledgerline.calc.TaxRuleSet
import dev.ledgerline.core.Activity
import dev.ledgerline.core.ActivityType.BUY
import dev.ledgerline.core.ActivityType.SELL
import dev.ledgerline.testsupport.Activities.SHARE
import dev.ledgerline.testsupport.L06Activities.roc
import dev.ledgerline.testsupport.L06Activities.trade
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.math.BigInteger
import java.time.LocalDate
import java.util.Locale
import java.util.TimeZone
import kotlin.random.Random

/**
 * L-06 phase B attacks. Only invariants, oracles and relations; no new tax values. The oracle restates
 * docs/tax-model.md `ACB-SOLD` (floor of total ACB x sold / held) in BigInteger.
 */
class L06AttackSpec : FunSpec({
    val engine = AcbEngine(CaIta2026v1)
    val u = CaIta2026v1.acbUnitsPerCent
    val sec = "SYN-ATK"
    val d = LocalDate.of(2025, 3, 3)

    fun pool() = AcbPool.empty("TP-0001", sec)

    fun step(
        p: AcbPool,
        a: PostedActivity,
    ): AcbPool = engine.apply(p, a).also { it.shouldBeInstanceOf<PoolStep.Applied>() }.pool

    fun buy(
        k: String,
        n: Long,
        cents: Long,
        c: Long,
        seq: Long,
        date: LocalDate = d,
    ) = trade(k, BUY, date, sec, n, cents, c, seq)

    fun sell(
        k: String,
        n: Long,
        cents: Long,
        c: Long,
        seq: Long,
        date: LocalDate = d,
    ) = trade(k, SELL, date, sec, n, cents, c, seq)

    test("L-06 attack: selling every share then buying again resets the pool cleanly") {
        val fresh = step(pool(), buy("b2", 7 * SHARE, 12_345, 99, 3))
        var p = step(pool(), buy("b1", 3 * SHARE, 10_001, 7, 1))
        p = step(p, sell("s1", 3 * SHARE, 9_000, 5, 2))
        p.shares shouldBe 0L
        p.totalAcb shouldBe 0L
        p = step(p, buy("b2", 7 * SHARE, 12_345, 99, 3))
        p.shares shouldBe fresh.shares
        p.totalAcb shouldBe fresh.totalAcb
    }

    test("L-06 attack: a zero-quantity sell on an empty pool does not crash the engine") {
        val r = engine.apply(pool(), sell("s0", 0L, 0, 0, 1))
        (r.pool.shares to r.pool.totalAcb) shouldBe (0L to 0L)
    }

    test("L-06 attack: 10k ops with repeating per-share ACB match the BigInteger oracle and conserve ACB exactly") {
        val rnd = Random(20_251_119)
        var p = pool()
        var shares = BigInteger.ZERO
        var acb = BigInteger.ZERO
        var cost = BigInteger.ZERO
        var removed = BigInteger.ZERO
        val acts = ArrayList<PostedActivity>()
        for (i in 1..10_000L) {
            val doSell = i % 3 == 0L && shares.signum() > 0
            if (doSell) {
                val n = (1 + rnd.nextLong(shares.toLong() / 2 + 1))
                val a = sell("s$i", n, rnd.nextLong(1, 5_000), rnd.nextLong(0, 50), i)
                acts += a
                val sold = acb * BigInteger.valueOf(n) / shares
                p = step(p, a)
                shares -= BigInteger.valueOf(n)
                acb -= sold
                removed += sold
            } else {
                val n = 3L * rnd.nextLong(1, 9_999_999) + 1 // odd unit counts: repeating decimals per share
                val cents = rnd.nextLong(1, 100_000)
                val c = rnd.nextLong(0, 99)
                val a = buy("b$i", n, cents, c, i)
                acts += a
                p = step(p, a)
                shares += BigInteger.valueOf(n)
                acb += BigInteger.valueOf((cents + c) * u)
                cost += BigInteger.valueOf((cents + c) * u)
            }
            p.shares shouldBe shares.toLong()
            p.totalAcb shouldBe acb.toLong()
        }
        (cost - removed) shouldBe BigInteger.valueOf(p.totalAcb)
        engine.replay(acts).pools[PoolKey("TP-0001", sec)]!!.totalAcb shouldBe p.totalAcb
    }

    test("L-06 attack: ROC to exactly zero ACB gives no gain; ROC on an empty pool never makes ACB negative") {
        val b = step(pool(), buy("b1", 10 * SHARE, 50_000, 0, 1))
        val exact = engine.apply(b, roc("r1", d, sec, 50_000, 2)).shouldBeInstanceOf<PoolStep.Applied>()
        exact.pool.totalAcb shouldBe 0L
        exact.pool.shares shouldBe 10 * SHARE
        exact.gains.shouldBeEmpty()
        val empty = engine.apply(pool(), roc("r2", d, sec, 1_000, 1)).shouldBeInstanceOf<PoolStep.Applied>()
        empty.pool.totalAcb shouldBe 0L
        empty.pool.shares shouldBe 0L
        empty.gains.sumOf { it.gain } shouldBe 1_000L * u
    }

    test("L-06 attack: commission larger than proceeds is a loss of (commission - proceeds) plus the ACB sold") {
        val b = step(pool(), buy("b1", 10 * SHARE, 10_000, 0, 1))
        val r = engine.apply(b, sell("s1", 4 * SHARE, 1_000, 3_000, 2)).shouldBeInstanceOf<PoolStep.Applied>()
        val g = r.gains.single()
        g.kind shouldBe GainKind.DISPOSITION
        g.gain shouldBe -2_000L * u - g.acbSold
        g.gainCents shouldBe CaIta2026v1.toReportedCents(g.gain)
        (g.gain < 0) shouldBe true
    }

    test("L-06 attack: overflow near Long.MAX fails loudly, never wraps") {
        shouldThrow<ArithmeticException> { engine.apply(pool(), buy("b1", SHARE, Long.MAX_VALUE / 2, 0, 1)) }
        shouldThrow<ArithmeticException> { engine.apply(pool(), buy("b2", SHARE, Long.MAX_VALUE / u, 1, 1)) }
        val big = step(pool(), buy("b3", Long.MAX_VALUE / 2 + 1, 100, 0, 1))
        shouldThrow<ArithmeticException> { engine.apply(big, buy("b4", Long.MAX_VALUE / 2 + 1, 100, 0, 2)) }
        val held = step(pool(), buy("b5", SHARE, 100, 0, 1))
        shouldThrow<ArithmeticException> {
            engine.apply(held, sell("s1", SHARE, Long.MAX_VALUE / u + 1, 0, 2))
        }
    }

    test("L-06 attack: same-date buy and sell follow sourceSeq, not arrival order") {
        val b = buy("b1", 5 * SHARE, 10_000, 0, 1)
        val s = sell("s1", 5 * SHARE, 12_000, 0, 2)
        val inOrder = engine.replay(listOf(b, s))
        val reversedArrival = engine.replay(listOf(s, b))
        inOrder.rejections.shouldBeEmpty()
        reversedArrival.outputHash shouldBe inOrder.outputHash
        // sell sequenced before the buy on the same date is rejected, whatever the arrival order
        val sBefore = sell("s2", 5 * SHARE, 12_000, 0, 1)
        val bAfter = buy("b2", 5 * SHARE, 10_000, 0, 2)
        for (arrival in listOf(listOf(sBefore, bAfter), listOf(bAfter, sBefore))) {
            val r = engine.replay(arrival)
            r.rejections.single().code shouldBe RejectionCode.INSUFFICIENT_SHARES
            r.gains.shouldBeEmpty()
        }
    }

    test("L-06 attack: outputHash is stable across default locale and time zone") {
        val acts =
            listOf(
                buy("b1", 3 * SHARE, 10_001, 7, 1),
                sell("s1", SHARE, 9_000, 5, 2, d.plusDays(1)),
                roc("r1", d.plusDays(2), sec, 100, 3),
            )
        val oldL = Locale.getDefault()
        val oldTz = TimeZone.getDefault()
        try {
            val hashes =
                listOf(
                    "en-CA" to "UTC",
                    "ar-SA" to "Pacific/Kiritimati",
                    "tr-TR" to "America/St_Johns",
                    "hi-IN" to "Etc/GMT+12",
                )
                    .map { (l, tz) ->
                        Locale.setDefault(Locale.forLanguageTag(l))
                        TimeZone.setDefault(TimeZone.getTimeZone(tz))
                        engine.replay(acts).outputHash
                    }
            hashes.toSet() shouldHaveSize 1
        } finally {
            Locale.setDefault(oldL)
            TimeZone.setDefault(oldTz)
        }
    }

    test("L-06 attack: a test-only TaxRuleSet with other rounding changes outputs and the hash (ADR 0004)") {
        val coarse =
            object : TaxRuleSet {
                override val id = "TEST-COARSE.1"
                override val ruleIds = CaIta2026v1.ruleIds
                override val acbUnitsPerCent = 100L

                override fun toReportedCents(internal: Long): Long = internal / acbUnitsPerCent

                override fun taxDate(activity: Activity): LocalDate = activity.effectiveDate
            }
        val acts =
            listOf(
                buy("b1", 3 * SHARE, 10_001, 7, 1),
                sell("s1", SHARE, 9_000, 5, 2, d.plusDays(1)),
            )
        val a = engine.replay(acts)
        val b = AcbEngine(coarse).replay(acts)
        b.ruleSetId shouldBe "TEST-COARSE.1"
        b.outputHash shouldNotBe a.outputHash
        val key = PoolKey("TP-0001", sec)
        b.pools[key]!!.totalAcb shouldNotBe a.pools[key]!!.totalAcb
        b.gains.single().acbSold * (u / 100) shouldNotBe 0L
        b.gains.single().acbSold shouldNotBe a.gains.single().acbSold
    }
})
