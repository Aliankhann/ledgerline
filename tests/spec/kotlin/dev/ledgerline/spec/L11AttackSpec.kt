package dev.ledgerline.spec

import dev.ledgerline.calc.AcbEngine
import dev.ledgerline.calc.BookValueComparison
import dev.ledgerline.calc.CaIta2026v1
import dev.ledgerline.calc.PoolKey
import dev.ledgerline.core.ActivityType
import dev.ledgerline.testsupport.L06Activities
import dev.ledgerline.testsupport.L07Activities
import dev.ledgerline.testsupport.L07Fixture
import dev.ledgerline.testsupport.SourceText
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import java.time.LocalDate

/**
 * L-11 phase B attack, round 1. No tax value is invented: the superficial loss amount is the cited fixture's
 * `expected.t2.denied.cents` (ACB-SLR Example #1); the commission amount is the activity's own input.
 */
class L11AttackSpec : FunSpec({

    val engine = AcbEngine(CaIta2026v1)
    val ex1 = L07Fixture.load("acb-slr-example1")
    val slKey = PoolKey(L06Activities.TAXPAYER, ex1.security)

    test("L-11 attack: an ACB raised by a superficial loss add-back is explained by an SL-* rule, not a catch-all") {
        val rows = listOf("t1", "t2", "t3").mapIndexed { i, p -> L07Activities.row(ex1, p, seq = i + 1L) }
        val saleEntry = rows[1].entryId
        val slPools = engine.replaySuperficial(rows).pools
        val bookPools = engine.replay(rows).pools
        val book = mapOf(slKey to CaIta2026v1.toReportedCents(bookPools.getValue(slKey).totalAcb))
        val d = BookValueComparison.compare(slPools, book).single()
        d.differenceCents shouldBe ex1.long("expected.t2.denied.cents")
        val sl = d.reasons.filter { it.ruleId.startsWith("SL-") }
        sl.sumOf { it.amountMinor } shouldBe ex1.long("expected.t2.denied.cents")
        sl.flatMap { it.entryIds } shouldContain saleEntry
        d.reasons.filter { it.ruleId == "POOL-IDENTICAL" }.sumOf { it.amountMinor } shouldBe 0L
    }

    test("L-11 attack: a buy commission that the book value omits is explained by COST-BUY-COMMISSION") {
        val key = PoolKey(L06Activities.TAXPAYER, "SYN-CCC")
        val buy =
            L06Activities.trade(
                "C1",
                ActivityType.BUY,
                LocalDate.of(2025, 3, 10),
                key.securityId,
                50_000_000L,
                225_000,
                495,
                1,
            )
        val pools = engine.replay(listOf(buy)).pools
        val d = BookValueComparison.compare(pools, mapOf(key to 225_000L)).single()
        d.differenceCents shouldBe 495L
        val r = d.reasons.single { it.ruleId == "COST-BUY-COMMISSION" }
        r.amountMinor shouldBe 495L
        r.entryIds shouldContain buy.entryId
        d.reasons.filter { it.ruleId == "POOL-IDENTICAL" }.sumOf { it.amountMinor } shouldBe 0L
    }

    test("L-11 attack: the acb-vs-book route is wired into Main.kt by a real call, not a comment") {
        val code = SourceText.withoutComments(SourceText.read("app/src/main/kotlin/dev/ledgerline/app/Main.kt"))
        // A call, not the declaration (`fun acbVsBookFromActivities(`) and not text inside a comment.
        Regex("""(?<!fun )\bacbVsBookFromActivities\(""").containsMatchIn(code) shouldBe true
    }

    test("L-11 attack: the comment stripper ignores comments and keeps strings (guards the wiring check)") {
        val src =
            "// acbVsBookFromActivities(x)\n/* a /* nested acbVsBookFromActivities( */ b */\n" +
                "val u = \"http://x\" // tail\nfoo()\n"
        val code = SourceText.withoutComments(src)
        code.contains("acbVsBookFromActivities") shouldBe false
        code.contains("\"http://x\"") shouldBe true
        code.contains("foo()") shouldBe true
    }
})
