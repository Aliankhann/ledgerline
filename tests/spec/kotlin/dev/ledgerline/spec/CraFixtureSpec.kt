package dev.ledgerline.spec

import dev.ledgerline.calc.AcbEngine
import dev.ledgerline.calc.AcbPool
import dev.ledgerline.calc.CaIta2026v1
import dev.ledgerline.calc.GainKind
import dev.ledgerline.calc.PoolKey
import dev.ledgerline.calc.SlOutcome
import dev.ledgerline.calc.TaxRuleSet
import dev.ledgerline.testsupport.CraFixture
import dev.ledgerline.testsupport.L06Activities
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import java.math.BigInteger

/**
 * Published worked examples (CRA pages, one CRA technical interpretation, one independent tool) run through the
 * narrowest calc APIs: `AcbEngine(CaIta2026v1).replay` and `.replaySuperficial`. Expected values come only from
 * `tests/fixtures/cra/`, each file citing its source URL and section. Rule set `CA-ITA-2026.1`.
 *
 * These are cross-checks of the arithmetic against published numbers, not new stories. Where a published figure
 * depends on the CRA's own rounding (the mutual fund chart) the test says so and asserts our exact figure as well.
 */
class CraFixtureSpec : FunSpec({

    val rules: TaxRuleSet = CaIta2026v1
    val engine = AcbEngine(rules)
    val rs = "[CA-ITA-2026.1]"

    fun poolAfter(
        f: CraFixture,
        count: Int,
    ): AcbPool {
        val replay = engine.replay(f.rows(count))
        replay.rejections.shouldBeEmpty()
        val key = PoolKey(L06Activities.TAXPAYER, f.security)
        return replay.pools[key] ?: AcbPool.empty(key.taxpayerId, key.securityId)
    }

    fun expectPool(
        f: CraFixture,
        prefix: String,
        pool: AcbPool,
    ) {
        if (f.has("$prefix.expected.shares.units")) pool.shares shouldBe f.long("$prefix.expected.shares.units")
        val cents = f.long("$prefix.expected.acb.cents")
        rules.toReportedCents(pool.totalAcb) shouldBe cents
        // Exact, not just after rounding: published ACB totals here are whole cents.
        pool.totalAcb shouldBe Math.multiplyExact(cents, rules.acbUnitsPerCent)
        val perShare = "$prefix.expected.acbPerShare.cents"
        if (f.has(perShare)) engine.acbPerShareCents(pool) shouldBe f.long(perShare)
    }

    test(
        "CRA fixture: STU Ltd. identical properties: ACB per share 15.00, 18.00, 18.00 after a sale, 20.63",
    ) {
        val f = CraFixture.load("cra-stu-identical")
        for (i in 1..4) {
            expectPool(f, "t$i", poolAfter(f, i))
        }
        // The CRA's 20.63 is 8,250 / 400 = 20.625 rounded to the cent; the total is exactly $8,250.00.
        val last = poolAfter(f, 4)
        last.totalAcb shouldBe Math.multiplyExact(825_000L, rules.acbUnitsPerCent)
        last.shares shouldBe 400_000_000L
    }

    test("CRA fixture: XYZ sale, proceeds 6,500 less commission 60 less ACB 4,000 is a gain of 2,440") {
        val f = CraFixture.load("cra-xyz-gain")
        val replay = engine.replay(f.rows(2))
        replay.rejections.shouldBeEmpty()
        replay.gains shouldHaveSize 1
        val line = replay.gains.single()
        line.kind shouldBe GainKind.DISPOSITION
        line.activityId shouldBe f.id("t2")
        line.taxDate shouldBe f.date("t2.date")
        line.gainCents shouldBe f.long("t2.expected.gain.cents")
        line.gain shouldBe Math.multiplyExact(f.long("t2.expected.gain.cents"), rules.acbUnitsPerCent)
        line.acbSold shouldBe Math.multiplyExact(f.long("t1.expected.acb.cents"), rules.acbUnitsPerCent)
        expectPool(f, "t2", poolAfter(f, 2))
    }

    test(
        "CRA fixture: mutual fund redemption: ACB per unit 15.20; unrounded ACB sold differs from CRA 3,040",
    ) {
        val f = CraFixture.load("cra-mutual-fund-redemption")
        val before = poolAfter(f, 4)
        before.shares shouldBe 1_802_048_900L
        // CRA: ACB per unit 15.20. The unrounded pooled ACB, shown to the cent, agrees.
        engine.acbPerShareCents(before) shouldBe f.long("cra.expected.acbPerUnit.cents")

        val replay = engine.replay(f.rows(5))
        replay.rejections.shouldBeEmpty()
        val line = replay.gains.single()

        // Oracle for OUR value, not a CRA value: exact rational arithmetic on the fixture's own cost and units.
        // ACB sold = total cost x units sold / units held; gain = (proceeds - fee) - ACB sold; round half up to cents.
        val cost = BigInteger.valueOf((1..4).sumOf { f.long("t$it.gross.cents") + f.long("t$it.commission.cents") })
        val held = BigInteger.valueOf((1..4).sumOf { f.long("t$it.shares.units") })
        val sold = BigInteger.valueOf(f.long("t5.shares.units"))
        val net = BigInteger.valueOf(f.long("t5.gross.cents") - f.long("t5.commission.cents"))

        fun roundHalfUp(
            num: BigInteger,
            den: BigInteger,
        ): Long = (num.multiply(BigInteger.TWO).add(den)).divide(den.multiply(BigInteger.TWO)).toLong()
        val oursAcbSoldCents = roundHalfUp(cost.multiply(sold), held)
        val oursGainCents = roundHalfUp(net.multiply(held).subtract(cost.multiply(sold)), held)

        // DIFFERENCE FROM THE CRA, asserted rather than hidden: the CRA multiplies 200 by the per-unit ACB rounded to
        // the cent (15.20) to get 3,040.00 and a gain of 374.00. Our pooled ACB is not rounded before it is
        // multiplied (invariant 8: rounding only at the reporting boundary), so 200 units carry 3,039.87 and the
        // gain is 374.13. The gap is at most 200 x half a cent = $1.00, the most the CRA's per-unit rounding can move it.
        oursAcbSoldCents shouldBe 303_987L
        oursGainCents shouldBe 37_413L
        rules.toReportedCents(line.acbSold) shouldBe oursAcbSoldCents
        line.gainCents shouldBe oursGainCents
        val gapCents = Math.abs(f.long("cra.expected.acbRedeemed.cents") - rules.toReportedCents(line.acbSold))
        (gapCents <= 100L) shouldBe true
        (Math.abs(f.long("cra.expected.gain.cents") - line.gainCents) <= 100L) shouldBe true
    }

    test(
        "CRA fixture: ROC to zero after five 200 distributions; a further 100 is a 100 capital gain",
    ) {
        val f = CraFixture.load("cra-roc-below-zero")
        for (i in 1..6) {
            val replay = engine.replay(f.rows(i))
            replay.rejections.shouldBeEmpty()
            expectPool(f, "t$i", replay.pools.getValue(PoolKey(L06Activities.TAXPAYER, f.security)))
            // No gain until the ACB is exhausted: $200 x 5 is exactly the $1,000 cost.
            replay.gains.shouldBeEmpty()
        }
        val replay = engine.replay(f.rows(7))
        replay.rejections.shouldBeEmpty()
        expectPool(f, "t7", replay.pools.getValue(PoolKey(L06Activities.TAXPAYER, f.security)))
        val line = replay.gains.single()
        line.kind shouldBe GainKind.DEEMED_GAIN_ROC
        line.activityId shouldBe f.id("t7")
        line.gainCents shouldBe f.long("t7.expected.gain.cents")
        line.gain shouldBe Math.multiplyExact(f.long("t7.expected.gain.cents"), rules.acbUnitsPerCent)
    }

    test(
        "CRA fixture: TI 2004-0073011E5 partial superficial loss, 20/50 denied, added to the 20 shares",
    ) {
        val f = CraFixture.load("cra-ti-2004-0073011e5-partial-sl")
        val result = engine.replaySuperficial(f.rows(4))
        result.rejections.shouldBeEmpty()
        val sale = result.sales.single { it.activityId == f.id("t3") }

        // S, P, B from the TI, as the rule set would see them.
        val sold = f.long("expected.sold.units")
        val acquired = f.long("expected.acquired.units")
        val held = f.long("expected.heldAtEnd.units")
        sold shouldBe 50_000_000L
        minOf(sold, acquired, held) shouldBe f.long("expected.fraction.numerator.units")
        sold shouldBe f.long("expected.fraction.denominator.units")

        // Only the ratio is asserted: denied x S = L x least(S, P, B), exactly in internal units. The dollar amounts
        // exist only because the fixture's prices are test assumptions.
        val loss = -sale.gainBeforeRule
        (loss > 0L) shouldBe true
        Math.multiplyExact(sale.denied, f.long("expected.fraction.denominator.units")) shouldBe
            Math.multiplyExact(loss, f.long("expected.fraction.numerator.units"))
        sale.reportedGain shouldBe sale.gainBeforeRule + sale.denied
        sale.outcome shouldBe SlOutcome.ADDED_TO_ACB

        // Add-back target: the 20 shares held at the end (the pool) carry the denied amount on top of their cost.
        sale.triggers.sumOf { it.shares } shouldBe acquired
        val pool = result.pools.getValue(PoolKey(L06Activities.TAXPAYER, f.security))
        pool.shares shouldBe f.long("expected.pool.shares.units")
        pool.totalAcb shouldBe
            Math.addExact(
                Math.multiplyExact(f.long("assumed.pool.acbBeforeAddBack.cents"), rules.acbUnitsPerCent),
                sale.denied,
            )
    }

    test(
        "CRA fixture: tsiemens/acb README demo (independent tool, not CRA): ACB 252.00, gain 9.60, ACB 201.60",
    ) {
        val f = CraFixture.load("tsiemens-bar-demo")
        expectPool(f, "t1", poolAfter(f, 1))
        val replay = engine.replay(f.rows(2))
        replay.rejections.shouldBeEmpty()
        val line = replay.gains.single()
        line.kind shouldBe GainKind.DISPOSITION
        line.gainCents shouldBe f.long("t2.expected.gain.cents")
        line.gain shouldBe Math.multiplyExact(f.long("t2.expected.gain.cents"), rules.acbUnitsPerCent)
        expectPool(f, "t2", poolAfter(f, 2))
    }

    test("CRA fixture: every file in tests/fixtures/cra loads only with a cited source (traceability)") {
        listOf(
            "cra-stu-identical",
            "cra-xyz-gain",
            "cra-mutual-fund-redemption",
            "cra-roc-below-zero",
            "cra-ti-2004-0073011e5-partial-sl",
            "tsiemens-bar-demo",
        ).forEach { CraFixture.load(it).string("source.id").isNotBlank() shouldBe true }
    }
})
