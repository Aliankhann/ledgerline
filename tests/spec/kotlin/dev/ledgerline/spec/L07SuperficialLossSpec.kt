package dev.ledgerline.spec

import dev.ledgerline.calc.AcbEngine
import dev.ledgerline.calc.AcbPool
import dev.ledgerline.calc.CaIta2026v1
import dev.ledgerline.calc.PoolKey
import dev.ledgerline.calc.PostedActivity
import dev.ledgerline.calc.SaleResult
import dev.ledgerline.calc.SlOutcome
import dev.ledgerline.calc.SlReplay
import dev.ledgerline.calc.TaxRuleSet
import dev.ledgerline.core.AccountType
import dev.ledgerline.testsupport.L06Activities
import dev.ledgerline.testsupport.L07Activities
import dev.ledgerline.testsupport.L07Fixture
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.collections.shouldContainAll
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

    test("L-07 AC1 $rs: full repurchase after the sale denies the whole loss; add-back lifts the pool ACB (ACB-SLR #1)") {
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
})
