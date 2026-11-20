package dev.ledgerline.spec

import dev.ledgerline.calc.AcbEngine
import dev.ledgerline.calc.CaIta2026v1
import dev.ledgerline.calc.PoolKey
import dev.ledgerline.calc.PostedActivity
import dev.ledgerline.calc.SaleResult
import dev.ledgerline.calc.SlOutcome
import dev.ledgerline.calc.SlReplay
import dev.ledgerline.calc.TaxRuleSet
import dev.ledgerline.core.AccountType
import dev.ledgerline.core.ActivityType
import dev.ledgerline.testsupport.Activities
import dev.ledgerline.testsupport.L06Activities
import dev.ledgerline.testsupport.L07Activities
import dev.ledgerline.testsupport.L07Fixture
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.time.LocalDate

/**
 * Story L-07 (docs/stories/L-07-superficial-loss.md), pure: no database. Every test assumes rule set `CA-ITA-2026.1`
 * (docs/tax-model.md, SL-* rules, PROPOSED) and names it. Expected values come only from `tests/fixtures/l07/`, each
 * file citing its published source. L-06 types are as in the KDoc of `L06PooledAcbSpec`.
 *
 * API contract (package `dev.ledgerline.calc`; Dev may add members, not change these). The SL rules stay pluggable
 * (ADR 0004): they are `TaxRuleSet` methods, never constants in the engine.
 *
 * - `TaxRuleSet` gains, and `CaIta2026v1` implements:
 *   - `fun superficialWindow(saleTaxDate: LocalDate): ClosedRange<LocalDate>`: `SL-WINDOW`, both ends inclusive; for
 *     the default rules `saleTaxDate - 30 days .. saleTaxDate + 30 days`. "Held at the end" is tested on its end date.
 *   - `fun superficialLossDenied(sold: Long, acquired: Long, heldAtEnd: Long, loss: Long): Long`: `SL-PARTIAL`,
 *     `least(sold, acquired, heldAtEnd) / sold x loss`; shares in 10^-6 units, `loss` > 0 and the result in
 *     internal ACB units (`acbUnitsPerCent`).
 *   - `ruleIds` also contains `SL-DEFINITION`, `SL-WINDOW`, `SL-IDENTICAL`, `SL-HELD-END`, `SL-PARTIAL`,
 *     `SL-REGISTERED`, `SL-ADDBACK-TARGET`, `SL-ADDBACK-TIMING`.
 * - `AcbEngine.replaySuperficial(activities: Collection<PostedActivity>): SlReplay`: like `replay`, canonical order
 *   (tax date, `sourceSeq`, idempotency key; never arrival order), but registered-account (TFSA, RRSP) acquisitions
 *   are read as possible substituted property; they never enter a pool and a registered disposition produces no
 *   [SaleResult]. Identical property = same security ID (`SL-IDENTICAL`), all accounts of the taxpayer.
 * - `data class SlReplay(val ruleSetId: String, val pools: Map<PoolKey, AcbPool>, val sales: List<SaleResult>,
 *   val rejections: List<Rejection>, val outputHash: String)`: `sales` has one entry per accepted non-registered
 *   disposition in canonical order; the hash covers the rule set ID, pools and sales.
 * - `data class SaleResult(val activityId: String, val taxDate: LocalDate, val gainBeforeRule: Long, val denied: Long,
 *   val reportedGain: Long, val outcome: SlOutcome, val triggers: List<SlTrigger>, val reason: String,
 *   val ruleIds: Set<String>)`: all amounts in internal ACB units, negative = loss. `denied >= 0`, 0 for a gain;
 *   `reportedGain = gainBeforeRule + denied`, so the allowed loss is `-reportedGain` when that is positive.
 *   `gainBeforeRule` of a later sale already includes any add-back made to the pool (AC1).
 * - `enum class SlOutcome { NONE, ADDED_TO_ACB, PERMANENTLY_LOST }`: `NONE` when `denied == 0`.
 * - `data class SlTrigger(val activityId: String, val accountType: AccountType, val shares: Long)`: one per
 *   substituted acquisition counted for the sale; `shares` in 10^-6 units.
 * - The add-back lands in the pool at the earliest substituted acquisition on or after the sale date
 *   (`SL-ADDBACK-TIMING`), so a replay that ends at that acquisition shows it in `pools`.
 */
class L07SuperficialLossSpec : FunSpec({

    val rules: TaxRuleSet = CaIta2026v1
    val rs = "[CA-ITA-2026.1]"
    val engine = AcbEngine(rules)
    val key = PoolKey(L06Activities.TAXPAYER, "SYN-A")

    fun units(cents: Long): Long = Math.multiplyExact(cents, rules.acbUnitsPerCent)

    fun rows(
        f: L07Fixture,
        vararg prefixes: String,
        accounts: Map<String, String> = emptyMap(),
    ): List<PostedActivity> =
        prefixes.mapIndexed { i, p -> L07Activities.row(f, p, seq = i + 1L, accountOverride = accounts[p]) }

    fun SlReplay.sale(
        f: L07Fixture,
        prefix: String,
    ): SaleResult = sales.single { it.activityId == L07Activities.id(f, prefix) }

    val ex1 = L07Fixture.load("acb-slr-example1")

    test("L-07 $rs: the rule set defines the SL rule IDs and a window of 30 days either side of the sale") {
        rules.ruleIds shouldContainAll
            setOf(
                "SL-DEFINITION", "SL-WINDOW", "SL-IDENTICAL", "SL-HELD-END",
                "SL-PARTIAL", "SL-REGISTERED", "SL-ADDBACK-TARGET", "SL-ADDBACK-TIMING",
            )
        val d = LocalDate.of(2025, 6, 2)
        rules.superficialWindow(d) shouldBe d.minusDays(30)..d.plusDays(30)
    }

    test(
        "L-07 AC1 $rs: full repurchase after the sale denies the whole loss; add-back lifts the pool ACB (ACB-SLR #1)",
    ) {
        val sale = engine.replaySuperficial(rows(ex1, "t1", "t2", "t3", "t4")).sale(ex1, "t2")
        sale.gainBeforeRule shouldBe -units(ex1.long("expected.t2.lossBeforeRule.cents"))
        sale.denied shouldBe units(ex1.long("expected.t2.denied.cents"))
        sale.reportedGain shouldBe -units(ex1.long("expected.t2.allowed.cents"))
        sale.outcome shouldBe SlOutcome.ADDED_TO_ACB
        val pool = engine.replaySuperficial(rows(ex1, "t1", "t2", "t3")).pools.getValue(key)
        pool.shares shouldBe ex1.long("expected.afterT3.shares.units")
        pool.totalAcb shouldBe units(ex1.long("expected.afterT3.acb.cents"))
    }

    test("L-07 AC1 $rs: the later sale reports the reduced gain of 3,000 not 5,000 (ACB-SLR #1)") {
        val later = engine.replaySuperficial(rows(ex1, "t1", "t2", "t3", "t4")).sale(ex1, "t4")
        rules.toReportedCents(later.reportedGain) shouldBe ex1.long("expected.t4.gain.cents")
        later.denied shouldBe 0L
        later.outcome shouldBe SlOutcome.NONE
    }

    test("L-07 AC3 $rs: a repurchase in another non-registered account gives the AC1 result (ACB-REG, ITA-54)") {
        val moved = rows(ex1, "t1", "t2", "t3", "t4", accounts = mapOf("t3" to "NR_B"))
        val result = engine.replaySuperficial(moved)
        result.sale(ex1, "t2").denied shouldBe units(ex1.long("expected.t2.denied.cents"))
        result.sale(ex1, "t2").outcome shouldBe SlOutcome.ADDED_TO_ACB
        rules.toReportedCents(result.sale(ex1, "t4").reportedGain) shouldBe ex1.long("expected.t4.gain.cents")
        val pool = engine.replaySuperficial(moved.take(3)).pools.getValue(key)
        pool.shares shouldBe ex1.long("expected.afterT3.shares.units")
        pool.totalAcb shouldBe units(ex1.long("expected.afterT3.acb.cents"))
    }

    test("L-07 AC10 $rs: arrival order does not change results or the output hash (ACB-SLR #1)") {
        val canonical = rows(ex1, "t1", "t2", "t3", "t4")
        val expected = engine.replaySuperficial(canonical)
        val orders = listOf(canonical.reversed(), listOf(canonical[2], canonical[3], canonical[0], canonical[1]))
        orders.forEach { arrival ->
            val got = engine.replaySuperficial(arrival)
            got shouldBe expected
            got.outputHash shouldBe expected.outputHash
        }
        expected.sales shouldHaveSize 2
        expected.sale(ex1, "t2").denied shouldBe units(ex1.long("expected.t2.denied.cents"))
    }

    test("L-07 $rs: the window is a rule-set value, not an engine constant (ADR 0004)") {
        val sameDayOnly =
            object : TaxRuleSet by CaIta2026v1 {
                override fun superficialWindow(saleTaxDate: LocalDate): ClosedRange<LocalDate> =
                    saleTaxDate..saleTaxDate
            }
        val result = AcbEngine(sameDayOnly).replaySuperficial(rows(ex1, "t1", "t2", "t3", "t4"))
        result.sale(ex1, "t2").denied shouldBe 0L
        result.sale(ex1, "t2").outcome shouldBe SlOutcome.NONE
    }

    val inKind = L07Fixture.load("acb-inkind-example2")
    val ex2 = L07Fixture.load("acb-slr-example2")

    test("L-07 AC4 $rs: repurchase in a TFSA is permanently lost, no add-back (ACB-INKIND #2)") {
        val all = rows(inKind, "t1", "t2", "t3")
        val result = engine.replaySuperficial(all)
        val sale = result.sale(inKind, "t2")
        sale.gainBeforeRule shouldBe -units(inKind.long("expected.t2.lossBeforeRule.cents"))
        sale.denied shouldBe units(inKind.long("expected.t2.denied.cents"))
        sale.reportedGain shouldBe -units(inKind.long("expected.t2.allowed.cents"))
        sale.outcome shouldBe SlOutcome.valueOf(inKind.string("expected.outcome"))
        val pool = result.pools.getValue(PoolKey(L06Activities.TAXPAYER, inKind.security))
        pool.shares shouldBe inKind.long("expected.afterT3.shares.units")
        pool.totalAcb shouldBe units(inKind.long("expected.afterT3.acb.cents"))
    }

    test("L-07 AC5 $rs: repurchase in an RRSP is permanently lost; pool stays 0 shares, 0 ACB (ACB-SLR #2)") {
        val result = engine.replaySuperficial(rows(ex2, "t1", "t2", "t3", "t4"))
        val sale = result.sale(ex2, "t2")
        sale.gainBeforeRule shouldBe -units(ex2.long("expected.t2.lossBeforeRule.cents"))
        sale.denied shouldBe units(ex2.long("expected.t2.denied.cents"))
        sale.reportedGain shouldBe -units(ex2.long("expected.t2.allowed.cents"))
        sale.outcome shouldBe SlOutcome.PERMANENTLY_LOST
        val pool = result.pools.getValue(PoolKey(L06Activities.TAXPAYER, ex2.security))
        pool.shares shouldBe ex2.long("expected.afterT2.shares.units")
        pool.totalAcb shouldBe units(ex2.long("expected.afterT2.acb.cents"))
    }

    test("L-07 AC5 $rs: the RRSP sale of 2015-12-02 produces no gain or loss in the non-registered ledger") {
        val result = engine.replaySuperficial(rows(ex2, "t1", "t2", "t3", "t4"))
        result.sales.map { it.activityId } shouldBe listOf(L07Activities.id(ex2, "t2"))
    }

    test("L-07 AC11 $rs: a denied loss names the sale, the TFSA purchase with its account type, and the outcome") {
        val sale = engine.replaySuperficial(rows(inKind, "t1", "t2", "t3")).sale(inKind, "t2")
        sale.triggers.map { it.activityId } shouldBe listOf(L07Activities.id(inKind, "t3"))
        sale.triggers.map { it.accountType } shouldBe listOf(AccountType.TFSA)
        sale.reason shouldContain L07Activities.id(inKind, "t2")
        sale.reason shouldContain L07Activities.id(inKind, "t3")
        sale.reason shouldContain "TFSA"
        sale.reason shouldContain inKind.string("expected.outcome")
        sale.ruleIds shouldContainAll setOf("SL-DEFINITION", "SL-REGISTERED")
    }

    test("L-07 AC11 $rs: an add-to-ACB denial says ADDED_TO_ACB and names the non-registered purchase") {
        val sale = engine.replaySuperficial(rows(ex1, "t1", "t2", "t3", "t4")).sale(ex1, "t2")
        sale.triggers.map { it.activityId } shouldBe listOf(L07Activities.id(ex1, "t3"))
        sale.triggers.map { it.accountType } shouldBe listOf(AccountType.NON_REGISTERED)
        sale.reason shouldContain L07Activities.id(ex1, "t3")
        sale.reason shouldContain "ADDED_TO_ACB"
        sale.ruleIds shouldContainAll setOf("SL-DEFINITION", "SL-ADDBACK-TARGET")
    }

    val partial = L07Fixture.load("taxtips-partial")
    val jeff = L07Fixture.load("acb-slr-jeff")
    val window = L07Fixture.load("ita54-window")

    /** Rows `a.t1`.. or `b.t1`.. of the TaxTips fixture; the fixture keeps one security for both cases. */
    fun partialRows(case: String): List<PostedActivity> {
        val prefixes = (1..4).map { "$case.t$it" }.filter { partial.has("$it.type") }
        return prefixes.mapIndexed { i, p -> L07Activities.row(partial, p, seq = i + 1L) }
    }

    test("L-07 AC6a $rs: S=50 P=20 B=20 L=1,000: 400 denied, 600 allowed, 400 added to the 20 shares (TAXTIPS)") {
        val result = engine.replaySuperficial(partialRows("a"))
        val sale = result.sale(partial, "a.t2")
        sale.gainBeforeRule shouldBe -units(partial.long("a.expected.lossBeforeRule.cents"))
        sale.denied shouldBe units(partial.long("a.expected.denied.cents"))
        sale.reportedGain shouldBe -units(partial.long("a.expected.allowed.cents"))
        sale.outcome shouldBe SlOutcome.ADDED_TO_ACB
        val pool = result.pools.getValue(PoolKey(L06Activities.TAXPAYER, partial.security))
        pool.shares shouldBe partial.long("a.expected.pool.shares.units")
        pool.totalAcb shouldBe units(partial.long("a.expected.pool.acb.cents"))
    }

    test("L-07 AC6b $rs: S=50 P=150 B=120 L=1,000: the whole loss is denied (TAXTIPS)") {
        val sale = engine.replaySuperficial(partialRows("b")).sale(partial, "b.t3")
        sale.gainBeforeRule shouldBe -units(partial.long("b.expected.lossBeforeRule.cents"))
        sale.denied shouldBe units(partial.long("b.expected.denied.cents"))
        sale.reportedGain shouldBe -units(partial.long("b.expected.allowed.cents"))
        sale.triggers.map { it.activityId }.toSet() shouldBe
            setOf(L07Activities.id(partial, "b.t2"), L07Activities.id(partial, "b.t4"))
    }

    test("L-07 AC6 $rs: the proration formula is a rule-set method: least(S,P,B)/S x L (TAXTIPS cases)") {
        val s = 50_000_000L
        rules.superficialLossDenied(s, 20_000_000L, 20_000_000L, units(100_000)) shouldBe units(40_000)
        rules.superficialLossDenied(s, 150_000_000L, 120_000_000L, units(100_000)) shouldBe units(100_000)
    }

    test("L-07 AC7 $rs: nothing held 30 days after the window: the loss is allowed in full (ACB-SLR, Jeff)") {
        val result = engine.replaySuperficial(rows(jeff, "t1", "t2", "t3", "t4"))
        val sale = result.sale(jeff, "t4")
        sale.denied shouldBe jeff.long("expected.t4.denied.cents")
        sale.outcome shouldBe SlOutcome.NONE
        sale.triggers.size shouldBe 0
        (sale.gainBeforeRule < 0L) shouldBe true
        sale.reportedGain shouldBe sale.gainBeforeRule
        result.pools.getValue(key).shares shouldBe jeff.long("expected.afterT4.shares.units")
    }

    /** The four AC8 shapes of `ita54-window.properties`: a sale on D and one identical purchase on a given day. */
    fun boundaryCase(n: Int): Pair<List<PostedActivity>, String> {
        val rebuyDate = window.date("case$n.rebuy.date")

        fun t(
            k: String,
            type: ActivityType,
            date: LocalDate,
            shares: Long,
            cents: Long,
            seq: Long,
        ) = L06Activities.trade("L07-win$n-$k", type, date, window.security, shares, cents, 0L, seq)
        val whole = window.long("sale.shares.units")
        val gross = { price: String -> window.long(price) * whole / Activities.SHARE }
        val setup = t("setup", ActivityType.BUY, window.date("setup.date"), whole, gross("setup.price.cents"), 1)
        val sale = t("sale", ActivityType.SELL, window.date("sale.date"), whole, gross("sale.price.cents"), 2)
        val rebuy = t("rebuy", ActivityType.BUY, rebuyDate, whole, gross("rebuy.price.cents"), 3)
        return listOf(setup, sale, rebuy) to "act-L07-win$n-sale"
    }

    (1..4).forEach { n ->
        val d = window.date("case$n.rebuy.date")
        val expected = window.bool("case$n.expected.superficial")
        test(
            "L-07 AC8 case $n $rs: repurchase on $d: superficial loss recorded = $expected (ITA-54, TAXTIPS 61 days)",
        ) {
            val (trades, saleId) = boundaryCase(n)
            val sale = engine.replaySuperficial(trades).sales.single { it.activityId == saleId }
            (sale.gainBeforeRule < 0L) shouldBe true
            (sale.denied > 0L) shouldBe expected
        }
    }

    test("L-07 AC9 $rs: a sale at a gain with a repurchase in the window is never flagged (ITA-54 definition)") {
        val result = engine.replaySuperficial(rows(jeff, "t1", "t2", "t3"))
        val sale = result.sale(jeff, "t2")
        (sale.gainBeforeRule > 0L) shouldBe true
        sale.denied shouldBe 0L
        sale.outcome shouldBe SlOutcome.NONE
        sale.reportedGain shouldBe sale.gainBeforeRule
        sale.triggers.size shouldBe 0
        result.pools.getValue(key).totalAcb shouldBe units(500_000)
    }

    xtest("L-07 AC2 $rs: purchase before the loss sale denies it (NEEDS SOURCED EXAMPLE, see story L-07 AC2)") {
        // Disabled by design. The story gives rule text only (ITA-54 "begins 30 days before") and no worked numbers;
        // QE does not invent them. Enable when the BA adds a sourced dated example and the owner approves it.
        // AC8 cases 3 and 4 already cover the before-sale boundary from rule text.
    }
})
