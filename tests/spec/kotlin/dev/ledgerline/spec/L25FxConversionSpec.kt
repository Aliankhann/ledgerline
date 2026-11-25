package dev.ledgerline.spec

import dev.ledgerline.calc.AcbEngine
import dev.ledgerline.calc.CaIta2026v1
import dev.ledgerline.calc.ExplainStatus
import dev.ledgerline.calc.HoldingExplanation
import dev.ledgerline.calc.PoolKey
import dev.ledgerline.calc.TaxYearReport
import dev.ledgerline.core.Currency
import dev.ledgerline.testsupport.L18
import dev.ledgerline.testsupport.L18.centsOf
import dev.ledgerline.testsupport.L18.parseCsv
import dev.ledgerline.testsupport.L25
import dev.ledgerline.testsupport.L25.cad
import dev.ledgerline.testsupport.L25.d
import dev.ledgerline.testsupport.L25.usdBuy
import dev.ledgerline.testsupport.L25.usdSell
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe

/**
 * L-25 story acceptance tests (docs/stories/L-25-fx-conversion.md), one per criterion. Provisional: the story is a
 * Draft. The API contract Dev must follow is the KDoc of `testsupport/L25.kt`. Every expected CAD figure is
 * `FxRateCache.toCadCents` of the committed Bank of Canada fixture rate (tests/fixtures/l08/); none is a tax claim.
 */
class L25FxConversionSpec : FunSpec({
    val share = L25.SHARE
    val tp = L25.tp
    val rules = CaIta2026v1
    val sec = "SYN-USD"
    val key = PoolKey(tp, sec)

    fun rateText(scaled: Long) = "${scaled / 10_000}.${(scaled % 10_000).toString().padStart(4, '0')}"

    test(
        "L-25 AC1: a USD buy converts (amount + commission) at its trade-date rate, step shows it",
    ) {
        val fx = L25.cache()
        val date = d("2025-03-04")
        // 50001 and 402 are chosen so that converting the parts separately (73028) differs from one rounding (73029).
        val buy = usdBuy("b1", sec, date, 5 * share, 50_001, 1, commission = 402)
        val rate = fx.rateFor(date)
        val expected = L25.cadAt(fx, 50_001 + 402, rate)
        withClue("the test must tell one rounding from two") {
            (cad(fx, 50_001, date) + cad(fx, 402, date)) shouldNotBe expected
        }
        val engine = AcbEngine(rules, fx)
        val pool = engine.replay(listOf(buy)).pools.getValue(key)
        rules.toReportedCents(pool.totalAcb) shouldBe expected
        val step = HoldingExplanation.explain(listOf(buy), null, key, fx)!!.steps.single()
        step.costDeltaCents shouldBe expected
        val shown = step.fx!!
        shown.currency shouldBe Currency.USD
        shown.originalMinor shouldBe 50_001 + 402
        shown.rateScaled shouldBe rate.rateScaled
        shown.rateDate shouldBe rate.date
        shown.cadCents shouldBe expected
    }

    test("L-25 AC2: a USD sell converts at its own trade-date rate; gain = CAD proceeds - CAD outlays - ACB sold") {
        val fx = L25.cache()
        val buyDate = d("2025-03-04")
        val sellDate = d("2025-03-13")
        withClue("buy and sell rates must differ for the test to mean anything") {
            fx.rateFor(buyDate).rateScaled shouldNotBe fx.rateFor(sellDate).rateScaled
        }
        val book =
            listOf(
                usdBuy("b1", sec, buyDate, 5 * share, 50_000, 1),
                usdSell("s1", sec, sellDate, 5 * share, 90_000, 2, commission = 1_000),
            )
        val row = TaxYearReport.build(book, tp, 2025, fx).rows.single()
        row.proceedsCents shouldBe cad(fx, 90_000, sellDate)
        row.outlaysCents shouldBe cad(fx, 1_000, sellDate)
        row.acbSoldCents shouldBe cad(fx, 50_000, buyDate)
        row.gainCents shouldBe row.proceedsCents - row.outlaysCents - row.acbSoldCents
        row.gainCents shouldNotBe cad(fx, 90_000 - 1_000 - 50_000, buyDate)
    }

    test("L-25 AC3: a weekend or holiday trade uses the last earlier rate and names its date") {
        val fx = L25.cache()
        // 2025-03-08 is a Saturday (last rate Fri 03-07); 2025-04-18 is Good Friday (last rate Thu 04-17).
        listOf("2025-03-08" to "2025-03-07", "2025-04-18" to "2025-04-17").forEach { (trade, published) ->
            val buy = usdBuy("b-$trade", sec, d(trade), 5 * share, 50_000, 1)
            val step = HoldingExplanation.explain(listOf(buy), null, key, fx)!!.steps.single()
            val shown = step.fx!!
            withClue("trade $trade") {
                shown.rateDate shouldBe d(published)
                shown.rateScaled shouldBe fx.rateFor(d(published)).rateScaled
                step.costDeltaCents shouldBe cad(fx, 50_000, d(published))
            }
        }
    }

    test(
        "L-25 AC4: a USD trade outside the rate files is rejected FX-RATE-MISSING, pool unchanged",
    ) {
        val fx = L25.cache()
        val held = usdBuy("b1", sec, d("2025-03-04"), 5 * share, 50_000, 1)
        val engine = AcbEngine(rules, fx)
        val before = engine.replay(listOf(held)).pools.getValue(key)
        val cases =
            listOf(
                usdBuy("early", sec, d("2025-03-01"), 5 * share, 7_000, 2),
                usdSell("late", sec, d("2026-01-06"), 5 * share, 9_000, 2),
                usdBuy("late-buy", sec, d("2026-01-06"), 5 * share, 9_000, 2),
            )
        cases.forEach { bad ->
            val book = listOf(held, bad)
            val replay = engine.replay(book)
            withClue(bad.activity.idempotencyKey) {
                replay.rejections.map { it.idempotencyKey } shouldBe listOf(bad.activity.idempotencyKey)
                replay.rejections.single().code.name shouldBe "FX_RATE_MISSING"
                replay.rejections.single().message.contains("FX-RATE-MISSING") shouldBe true
                val after = replay.pools.getValue(key)
                after.shares shouldBe before.shares
                after.totalAcb shouldBe before.totalAcb
                val reasons = HoldingExplanation.explain(book, null, key, fx)!!.reasons
                reasons.filter { it.ruleId == "FX-RATE-MISSING" }.flatMap { it.entryIds } shouldBe listOf(2L)
                val warned = TaxYearReport.build(book, tp, bad.activity.effectiveDate.year, fx).warnings
                warned.map { it.ruleId } shouldContain "FX-RATE-MISSING"
            }
        }
        // A rejected buy alone leaves no pool value and is still reported.
        val only = listOf(cases[0])
        val ex = HoldingExplanation.explain(only, null, key, fx)!!
        ex.acbCents shouldBe 0L
        ex.reasons.map { it.ruleId } shouldContain "FX-RATE-MISSING"
    }

    test("L-25 AC5: the tax year counts USD sales as CAD rows; no FX-NOT-CONVERTED anywhere") {
        val fx = L25.cache()
        val buyDate = d("2025-03-04")
        val sellDate = d("2025-06-30")
        val book =
            listOf(
                usdBuy("b1", sec, buyDate, 5 * share, 50_000, 1),
                usdSell("s1", sec, sellDate, 5 * share, 90_000, 2),
            )
        val year = TaxYearReport.build(book, tp, 2025, fx)
        val row = year.rows.single()
        row.currency shouldBe Currency.CAD
        row.proceedsCents shouldBe cad(fx, 90_000, sellDate)
        row.gainCents shouldBe cad(fx, 90_000, sellDate) - cad(fx, 50_000, buyDate)
        withClue("notes ${row.notes}") {
            val rate = rateText(fx.rateFor(sellDate).rateScaled)
            row.notes.any { "USD" in it && "900.00" in it && rate in it } shouldBe true
        }
        year.warnings.map { it.ruleId } shouldNotContain "FX-NOT-CONVERTED"
        year.totals.netCents shouldBe row.gainCents
        year.totals.gainsCents shouldBe row.gainCents
        TaxYearReport.yearsWithRows(book, tp, fx) shouldBe listOf(2025)

        val json = L18.year(book, 2025, fx = fx)
        json.getValue("currency").toString().trim('"') shouldBe "CAD"
        val csv = L18.fetch(book, "/v1/tax-year/$tp/2025.csv", fx = fx).body
        csv.contains("NOT-CONVERTED") shouldBe false
        val recs = parseCsv(csv)
        val sale = recs.single { it.getOrNull(L18.CSV_HEADER.indexOf("security")) == sec }
        centsOf(sale[L18.CSV_HEADER.indexOf("proceeds")]) shouldBe row.proceedsCents
        centsOf(recs.last()[L18.CSV_HEADER.indexOf("gain")]) shouldBe row.gainCents
    }

    test("L-25 AC6: a book value at one single rate is explained as FX-HISTORICAL") {
        val fx = L25.cache()
        // Whole-dollar amounts: every conversion below is an exact number of cents (no rounding tie).
        val book =
            listOf(
                usdBuy("b1", sec, d("2025-03-04"), 5 * share, 10_000, 1),
                usdBuy("b2", sec, d("2025-03-10"), 5 * share, 20_000, 2),
            )
        val acb = cad(fx, 10_000, d("2025-03-04")) + cad(fx, 20_000, d("2025-03-10"))
        val single = fx.rateFor(d("2025-03-14"))
        val bookValue = L25.cadAt(fx, 30_000, single)
        val ex = HoldingExplanation.explain(book, bookValue, key, fx)!!
        ex.acbCents shouldBe acb
        ex.differenceCents shouldBe acb - bookValue
        val historical = ex.reasons.single { it.ruleId == "FX-HISTORICAL" }
        historical.entryIds.sorted() shouldBe listOf(1L, 2L)
        historical.amountMinor shouldBe acb - bookValue
        ex.fullyExplained shouldBe true
        ex.status shouldBe ExplainStatus.EXPLAINED
        ex.adjustmentCount shouldBe 1
        // Control: a book value equal to the trade-date ACB needs no FX reason.
        val same = HoldingExplanation.explain(book, acb, key, fx)!!
        same.status shouldBe ExplainStatus.MATCHES
        same.reasons.map { it.ruleId } shouldNotContain "FX-HISTORICAL"
    }

    test("L-25 AC7: USD replay is deterministic and order independent; CAD never looks up a rate") {
        val fx = L25.cache()
        val usdBook =
            listOf(
                usdBuy("b1", sec, d("2025-03-04"), 5 * share, 50_001, 1, commission = 402),
                usdBuy("b2", sec, d("2025-03-10"), 3 * share, 30_000, 2),
                usdSell("s1", sec, d("2025-03-13"), 4 * share, 70_000, 3, commission = 1_000),
            )
        val engine = AcbEngine(rules, fx)
        val canonical = engine.replay(usdBook)
        canonical.rejections.shouldBeEmpty()
        val buysOnly = engine.replay(usdBook.take(2)).pools.getValue(key)
        rules.toReportedCents(buysOnly.totalAcb) shouldBe
            L25.cadAt(fx, 50_001 + 402, fx.rateFor(d("2025-03-04"))) + cad(fx, 30_000, d("2025-03-10"))
        engine.replay(usdBook).outputHash shouldBe canonical.outputHash
        engine.replay(usdBook.reversed()).outputHash shouldBe canonical.outputHash
        AcbEngine(rules, L25.cache()).replay(usdBook.shuffled(kotlin.random.Random(25))).outputHash shouldBe
            canonical.outputHash

        val cadBook =
            listOf(
                L18.buy("c1", "SYN-CAD", d("2025-03-04"), 5 * share, 50_000, 1),
                L18.sell("c2", "SYN-CAD", d("2025-03-13"), 5 * share, 90_000, 2),
            )
        val fresh = L25.cache()
        AcbEngine(rules, fresh).replaySuperficial(cadBook)
        TaxYearReport.build(cadBook, tp, 2025, fresh).rows.size shouldBe 1
        HoldingExplanation.explain(cadBook, null, PoolKey(tp, "SYN-CAD"), fresh)!!.steps.size shouldBe 2
        fresh.lookupCount shouldBe 0
    }
})
