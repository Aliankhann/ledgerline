package dev.ledgerline.spec

import dev.ledgerline.calc.AcbEngine
import dev.ledgerline.calc.CaIta2026v1
import dev.ledgerline.calc.PoolKey
import dev.ledgerline.calc.PostedActivity
import dev.ledgerline.calc.SlOutcome
import dev.ledgerline.calc.TaxRuleSet
import dev.ledgerline.core.AccountType
import dev.ledgerline.core.Activity
import dev.ledgerline.core.ActivityType
import dev.ledgerline.testsupport.Activities.SHARE
import dev.ledgerline.testsupport.L06Activities
import dev.ledgerline.testsupport.L07Activities
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import java.time.LocalDate
import kotlin.random.Random

/**
 * L-07 phase B attacks, rule set `CA-ITA-2026.1`. Relations and invariants only: no tax number is asserted that is
 * not a ratio or an equality between two runs of the same trades.
 */
class L07AttackSpec : FunSpec({
    val engine = AcbEngine(CaIta2026v1)
    val sec = "SYN-ATK"
    val sale = LocalDate.of(2025, 6, 30)
    val key = PoolKey(L06Activities.TAXPAYER, sec)
    var n = 0L

    fun t(
        id: String,
        type: ActivityType,
        date: LocalDate,
        shares: Long,
        cents: Long,
        account: String = "NR_A",
    ): PostedActivity {
        n += 1
        return L06Activities.trade(
            key = "ATK-$id",
            type = type,
            date = date,
            security = sec,
            shares = shares * SHARE,
            grossCents = shares * cents,
            commissionCents = 0L,
            seq = n,
            accountId = L07Activities.accountId(account),
            accountType = L07Activities.accountType(account),
        )
    }

    fun buy(
        id: String,
        date: LocalDate,
        shares: Long,
        cents: Long,
        acct: String = "NR_A",
    ) = t(id, ActivityType.BUY, date, shares, cents, acct)

    fun sell(
        id: String,
        date: LocalDate,
        shares: Long,
        cents: Long,
    ) = t(id, ActivityType.SELL, date, shares, cents)

    val base = buy("base", sale.minusDays(200), 100, 5000)

    test("L-07 attack: rebuy on day +30 denies, day +31 does not") {
        val on =
            engine.replaySuperficial(
                listOf(base, sell("s", sale, 100, 3000), buy("r", sale.plusDays(30), 100, 3000)),
            )
        on.sales.single().outcome shouldBe SlOutcome.ADDED_TO_ACB
        val off =
            engine.replaySuperficial(
                listOf(base, sell("s", sale, 100, 3000), buy("r", sale.plusDays(31), 100, 3000)),
            )
        off.sales.single().denied shouldBe 0L
        off.sales.single().outcome shouldBe SlOutcome.NONE
    }

    test("L-07 attack: extra buy on day -30 denies, day -31 does not") {
        fun run(offset: Long) =
            engine.replaySuperficial(
                listOf(base, buy("x", sale.minusDays(offset), 100, 3000), sell("s", sale, 100, 3000)),
            ).sales.single()
        run(30).denied shouldNotBe 0L
        run(31).denied shouldBe 0L
    }

    test("L-07 attack: repurchase split across non-registered and TFSA: whole loss denied, added share is pro rata") {
        val r =
            engine.replaySuperficial(
                listOf(
                    base,
                    sell("s", sale, 100, 3000),
                    buy("nr", sale.plusDays(3), 40, 3000),
                    buy("tf", sale.plusDays(4), 60, 3000, "TFSA"),
                ),
            )
        val s = r.sales.single()
        s.denied shouldBe -s.gainBeforeRule
        s.reportedGain shouldBe 0L
        s.triggers.sumOf { it.shares } shouldBe 100 * SHARE
        s.triggers.filter { it.accountType == AccountType.TFSA }.sumOf { it.shares } shouldBe 60 * SHARE
        s.outcome shouldBe SlOutcome.ADDED_TO_ACB
        // 40% of the denied loss goes into the 40-share pool on top of its purchase cost
        val pool = r.pools.getValue(key)
        pool.shares shouldBe 40 * SHARE
        pool.totalAcb shouldBe 40 * 3000 * CaIta2026v1.acbUnitsPerCent + s.denied * 40 / 100
    }

    test("L-07 attack: two loss sales in one window never count one acquired share twice") {
        val r =
            engine.replaySuperficial(
                listOf(
                    base,
                    sell("s1", sale, 50, 3000),
                    sell("s2", sale.plusDays(5), 50, 3000),
                    buy("r", sale.plusDays(10), 50, 3000),
                ),
            )
        r.sales.size shouldBe 2
        r.sales.flatMap { it.triggers }.filter { it.activityId.endsWith("-r") }.sumOf { it.shares } shouldBe
            50 * SHARE
    }

    test("L-07 attack: nothing held at the end of the window means no denial for either sale") {
        val r =
            engine.replaySuperficial(
                listOf(
                    base,
                    sell("s1", sale, 100, 3000),
                    buy("r", sale.plusDays(5), 100, 3000),
                    sell("s2", sale.plusDays(10), 100, 2000),
                ),
            )
        r.sales.size shouldBe 2
        r.sales.forEach {
            it.denied shouldBe 0L
            it.outcome shouldBe SlOutcome.NONE
        }
    }

    test("L-07 attack: changing the SL window in a test rule set changes outcome and outputHash") {
        val narrow =
            object : TaxRuleSet by CaIta2026v1 {
                override fun superficialWindow(saleTaxDate: LocalDate) =
                    saleTaxDate.minusDays(5)..saleTaxDate.plusDays(5)
            }
        val trades = listOf(base, sell("s", sale, 100, 3000), buy("r", sale.plusDays(20), 100, 3000))
        val a = engine.replaySuperficial(trades)
        val b = AcbEngine(narrow).replaySuperficial(trades)
        a.sales.single().denied shouldNotBe 0L
        b.sales.single().denied shouldBe 0L
        a.outputHash shouldNotBe b.outputHash
    }

    test("L-07 attack: a rule set without SL methods fails loudly in replaySuperficial, not silently") {
        val bare =
            object : TaxRuleSet {
                override val id = "TEST-BARE.1"
                override val ruleIds = CaIta2026v1.ruleIds
                override val acbUnitsPerCent = 100L

                override fun toReportedCents(internal: Long): Long = internal / acbUnitsPerCent

                override fun taxDate(activity: Activity): LocalDate = activity.effectiveDate
            }
        shouldThrow<UnsupportedOperationException> {
            AcbEngine(bare).replaySuperficial(listOf(base, sell("s", sale, 100, 3000)))
        }
    }

    test("L-07 attack: arrival order does not change sales, pools or outputHash (50 shuffles)") {
        val trades =
            listOf(
                base,
                sell("s1", sale, 60, 3000),
                buy("r1", sale.plusDays(2), 20, 3000),
                buy("r2", sale.plusDays(9), 30, 3100, "TFSA"),
                sell("s2", sale.plusDays(12), 10, 2500),
                buy("r3", sale.minusDays(10), 15, 4000),
            )
        val expected = engine.replaySuperficial(trades)
        repeat(50) { seed ->
            val got = engine.replaySuperficial(trades.shuffled(Random(seed)))
            got.outputHash shouldBe expected.outputHash
            got.sales shouldBe expected.sales
            got.pools shouldBe expected.pools
        }
    }
})
