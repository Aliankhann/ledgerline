package dev.ledgerline.spec

import dev.ledgerline.calc.AcbEngine
import dev.ledgerline.calc.CaIta2026v1
import dev.ledgerline.calc.ExplainStatus
import dev.ledgerline.calc.HoldingExplanation
import dev.ledgerline.calc.PoolKey
import dev.ledgerline.calc.PostedActivity
import dev.ledgerline.testsupport.L17
import dev.ledgerline.testsupport.L18
import dev.ledgerline.testsupport.L25
import dev.ledgerline.testsupport.L25.cad
import dev.ledgerline.testsupport.L25.d
import dev.ledgerline.testsupport.L25.usdBuy
import dev.ledgerline.testsupport.L25.usdSell
import dev.ledgerline.testsupport.UiSource
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain

/**
 * L-27 story acceptance tests (docs/stories/L-27-combined-adjustments.md), one per criterion. Provisional: the story is
 * a Draft. No figure here is a tax claim: every expected amount is a difference of the engine's own numbers, read from
 * its replays (with and without commissions, with and without the superficial-loss add-back) and from
 * `FxRateCache.toCadCents` at the L-08 fixture rates.
 *
 * Reading of the story that the tests commit to (Dev may contest in `docs/status.md`):
 * - FX amount = ACB at trade-date rates, commissions excluded, minus the broker-style cost (gross USD at the single
 *   rate). Commission amount = the engine's pool ACB with commissions minus without (CAD, trade-date rates), as in
 *   L-26 AC4. SL amount = ACB after the superficial-loss replay minus the plain replay (L-26 AC2).
 * - Reason order is FX-HISTORICAL, SL-ADDBACK-TARGET, COMMISSION-NOT-IN-BOOK (AC2, owner decision proposed).
 * - The footer sentence is rendered by the UI from `fullyExplained` and `adjustmentCount` (L-17 AC8 guard), so the
 *   footer is asserted through those two values plus the existing UI source guard.
 */
class L27CombinedAdjustmentsSpec : FunSpec({
    val share = L25.SHARE
    val fx = L25.cache()
    val rules = CaIta2026v1
    val engine = AcbEngine(rules, fx)
    val sec = "SYN-L27"
    val key = PoolKey(L25.tp, sec)
    val fxId = "FX-HISTORICAL"
    val slId = "SL-ADDBACK-TARGET"
    val comId = "COMMISSION-NOT-IN-BOOK"

    // 2025-03-14 is a published day that is none of the trade dates below (checked in each test).
    val single = fx.rateFor(d("2025-03-14"))

    fun cents(
        rows: List<PostedActivity>,
        superficial: Boolean,
    ): Long =
        rules.toReportedCents(
            if (superficial) {
                engine.replaySuperficial(rows).pools.getValue(key).totalAcb
            } else {
                engine.replay(rows).pools.getValue(key).totalAcb
            },
        )

    /** Whole-dollar USD amounts so no conversion below depends on a rounding tie. */
    fun usdBook(commissions: Boolean): List<PostedActivity> {
        fun c(v: Long) = if (commissions) v else 0L
        return listOf(
            usdBuy("b1", sec, d("2025-03-04"), 10 * share, 100_000, 1, commission = c(500)),
            usdSell("s1", sec, d("2025-03-06"), 10 * share, 40_000, 2),
            usdBuy("b3", sec, d("2025-03-10"), 3 * share, 18_000, 3, commission = c(400)),
            usdBuy("b4", sec, d("2025-03-11"), 2 * share, 12_000, 4, commission = c(700)),
        )
    }

    val heldGrossUsd = 18_000L + 12_000L

    test("L-27 AC1: FX-HISTORICAL carries only the rate-difference part, the remainder is passed on") {
        val withCom = usdBook(true)
        val noCom = usdBook(false)
        listOf("2025-03-10", "2025-03-11").forEach { fx.rateFor(d(it)).rateScaled shouldNotBe single.rateScaled }
        val book = L25.cadAt(fx, heldGrossUsd, single)
        val fxPart = cents(noCom, superficial = false) - book
        val commissionPart = cents(withCom, superficial = false) - cents(noCom, superficial = false)
        val slPart = cents(withCom, superficial = true) - cents(withCom, superficial = false)
        withClue("the scenario must have three distinct non-zero parts") {
            listOf(fxPart, commissionPart, slPart).forEach { it shouldNotBe 0L }
        }
        val ex = HoldingExplanation.explain(withCom, book, key, fx)!!
        val fxReason = ex.reasons.single { it.ruleId == fxId }
        fxReason.amountMinor shouldBe fxPart
        (fxReason.amountMinor != ex.differenceCents) shouldBe true
        ex.differenceCents shouldBe fxPart + commissionPart + slPart
    }

    test("L-27 AC2: after FX the SL add-back is tried first, then the commission, in that order") {
        val withCom = usdBook(true)
        val noCom = usdBook(false)
        val book = L25.cadAt(fx, heldGrossUsd, single)
        val ex = HoldingExplanation.explain(withCom, book, key, fx)!!
        ex.reasons.map { it.ruleId }.filter { it in setOf(fxId, slId, comId) } shouldBe listOf(fxId, slId, comId)
        ex.reasons.single { it.ruleId == slId }.amountMinor shouldBe
            cents(withCom, superficial = true) - cents(withCom, superficial = false)
        ex.reasons.single { it.ruleId == comId }.amountMinor shouldBe
            cents(withCom, superficial = false) - cents(noCom, superficial = false)
        ex.reasons.single { it.ruleId == slId }.entryIds.contains(3L) shouldBe true
        ex.reasons.single { it.ruleId == comId }.entryIds.sorted() shouldBe listOf(3L, 4L)
    }

    test("L-27 AC3: FX + SL add-back + commission shows exactly three reasons that sum to the difference") {
        val withCom = usdBook(true)
        val book = L25.cadAt(fx, heldGrossUsd, single)
        val ex = HoldingExplanation.explain(withCom, book, key, fx)!!
        ex.reasons.map { it.ruleId }.sorted() shouldBe listOf(comId, fxId, slId).sorted()
        ex.reasons.sumOf { it.amountMinor } shouldBe ex.differenceCents
        ex.differenceCents shouldBe cents(withCom, superficial = true) - book
        ex.status shouldBe ExplainStatus.EXPLAINED
        ex.fullyExplained shouldBe true
        ex.adjustmentCount shouldBe 3
        ex.unexplainedCents shouldBe 0L
        // The footer "Difference fully explained by N adjustments" is rendered from these two values (L-17 AC8 guard).
        UiSource.all() shouldContain "Difference fully explained by"
    }

    test("L-27 AC4: a holding whose whole difference is FX still shows one FX-HISTORICAL with the whole amount") {
        val book2 =
            listOf(
                usdBuy("b1", sec, d("2025-03-04"), 5 * share, 10_000, 1),
                usdBuy("b2", sec, d("2025-03-10"), 5 * share, 20_000, 2),
            )
        val acb = cad(fx, 10_000, d("2025-03-04")) + cad(fx, 20_000, d("2025-03-10"))
        val bookValue = L25.cadAt(fx, 30_000, fx.rateFor(d("2025-03-14")))
        val ex = HoldingExplanation.explain(book2, bookValue, key, fx)!!
        ex.reasons.size shouldBe 1
        ex.reasons.single().ruleId shouldBe fxId
        ex.reasons.single().amountMinor shouldBe acb - bookValue
        ex.adjustmentCount shouldBe 1
        ex.fullyExplained shouldBe true
        // Without a cache the old CAD behaviour is untouched: SL + commission still combine (L-26 AC2).
        val cadRows =
            listOf(
                L18.buy("c1", "SYN-CAD", d("2025-02-03"), 10 * share, 100_000, 1, commission = 500),
                L18.sell("c2", "SYN-CAD", d("2025-03-03"), 10 * share, 20_000, 2),
                L18.buy("c3", "SYN-CAD", d("2025-03-10"), 5 * share, 30_000, 3, commission = 400),
            )
        val cadKey = PoolKey(L18.tp, "SYN-CAD")
        val e = L17.explain(cadRows, L17.plainCents(cadRows, cadKey) - 400, cadKey)
        e.reasons.map { it.ruleId }.filter { it != "UNEXPLAINED" } shouldBe listOf(slId, comId)
        e.fullyExplained shouldBe true
    }

    test("L-27 AC5: a remainder that matches nothing stays UNEXPLAINED and the footer is not shown") {
        val withCom = usdBook(true)
        val book = L25.cadAt(fx, heldGrossUsd, single)
        // 1 cent more than the three adjustments explain; no detector may absorb it.
        val ex = HoldingExplanation.explain(withCom, book - 1, key, fx)!!
        ex.reasons.map { it.ruleId } shouldNotBe listOf(fxId, slId, comId)
        ex.fullyExplained shouldBe false
        ex.status shouldBe ExplainStatus.DISCREPANCY
        ex.unexplainedCents shouldNotBe 0L
        // CAD control: an SL add-back is attributed, the 1 cent left over is not forced onto the commission.
        val cadRows =
            listOf(
                L18.buy("c1", "SYN-CAD", d("2025-02-03"), 10 * share, 100_000, 1, commission = 500),
                L18.sell("c2", "SYN-CAD", d("2025-03-03"), 10 * share, 20_000, 2),
                L18.buy("c3", "SYN-CAD", d("2025-03-10"), 5 * share, 30_000, 3, commission = 400),
            )
        val cadKey = PoolKey(L18.tp, "SYN-CAD")
        val e = L17.explain(cadRows, L17.plainCents(cadRows, cadKey) - 1, cadKey)
        e.reasons.map { it.ruleId } shouldNotBe listOf(slId, comId)
        e.fullyExplained shouldBe false
        e.unexplainedCents shouldBe 1L
    }
})
