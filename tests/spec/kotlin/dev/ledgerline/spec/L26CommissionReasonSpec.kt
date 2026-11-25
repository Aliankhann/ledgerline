package dev.ledgerline.spec

import dev.ledgerline.calc.ExplainStatus
import dev.ledgerline.calc.PoolKey
import dev.ledgerline.calc.PostedActivity
import dev.ledgerline.testsupport.L17
import dev.ledgerline.testsupport.L18
import dev.ledgerline.testsupport.L25.d
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe

/**
 * L-26 story acceptance tests (docs/stories/L-26-commission-reason.md), one per criterion. Provisional: the story is a
 * Draft. Expected amounts are sums of the input commissions, or differences of the engine's own pool ACB (AC4); no
 * figure here is a tax claim. The subset search limit (owner decision) is only exercised with at most three buys.
 */
class L26CommissionReasonSpec : FunSpec({
    val share = L18.SHARE
    val sec = "SYN-COM"
    val key = PoolKey(L18.tp, sec)
    val ruleId = "COMMISSION-NOT-IN-BOOK"

    fun buy(
        k: String,
        date: String,
        shares: Long,
        gross: Long,
        entry: Long,
        commission: Long,
    ) = L18.buy(k, sec, d(date), shares * share, gross, entry, commission = commission)

    fun threeBuys() =
        listOf(
            buy("b1", "2025-02-03", 10, 100_000, 1, 300),
            buy("b2", "2025-02-10", 10, 100_000, 2, 500),
            buy("b3", "2025-02-17", 10, 100_000, 3, 700),
        )

    fun reasonsOf(
        rows: List<PostedActivity>,
        book: Long,
    ) = L17.explain(rows, book, key).reasons.map { Triple(it.ruleId, it.entryIds, it.amountMinor) }

    test("L-26 AC1: a commission-only difference is one COMMISSION-NOT-IN-BOOK reason, fully explained") {
        val rows = threeBuys().take(2)
        val acb = L17.cents(rows, key)
        val ex = L17.explain(rows, acb - (300 + 500), key)
        val reason = ex.reasons.single { it.ruleId == ruleId }
        reason.amountMinor shouldBe 800L
        reason.entryIds shouldContainExactlyInAnyOrder listOf(1L, 2L)
        ex.reasons.size shouldBe 1
        ex.status shouldBe ExplainStatus.EXPLAINED
        ex.fullyExplained shouldBe true
        ex.adjustmentCount shouldBe 1
        ex.unexplainedCents shouldBe 0L
    }

    test("L-26 AC2: with a superficial-loss add-back, the commission reason is the remainder; reasons sum exactly") {
        // Sell all 10 at a loss, rebuy 5 within 30 days and still hold them: part of the loss is denied and added back.
        val rows =
            listOf(
                buy("b1", "2025-02-03", 10, 100_000, 1, 500),
                L18.sell("s1", sec, d("2025-03-03"), 10 * share, 20_000, 2),
                buy("b3", "2025-03-10", 5, 30_000, 3, 400),
            )
        val acb = L17.cents(rows, key)
        val plain = L17.plainCents(rows, key)
        (acb - plain) shouldNotBe 0L
        // A broker that ignores the add-back and the rebuy's commission.
        val ex = L17.explain(rows, plain - 400, key)
        val reason = ex.reasons.single { it.ruleId == ruleId }
        reason.amountMinor shouldBe 400L
        reason.entryIds shouldBe listOf(3L)
        ex.reasons.filter { it.ruleId != "UNEXPLAINED" }.sumOf { it.amountMinor } shouldBe ex.differenceCents
        ex.fullyExplained shouldBe true
        ex.unexplainedCents shouldBe 0L
        // A subset of whole buys: book value omits the commissions of buys 1 and 3 only.
        val three = threeBuys()
        val sub = L17.explain(three, L17.cents(three, key) - (300 + 700), key)
        val subReason = sub.reasons.single { it.ruleId == ruleId }
        subReason.amountMinor shouldBe 1_000L
        subReason.entryIds shouldContainExactlyInAnyOrder listOf(1L, 3L)
        sub.fullyExplained shouldBe true
    }

    test("L-26 AC3: no subset of buy commissions equals the remainder, so no reason and not fully explained") {
        val rows = threeBuys()
        // Subset sums are 0, 300, 500, 700, 800, 1000, 1200, 1500; 400 is not among them.
        val ex = L17.explain(rows, L17.cents(rows, key) - 400, key)
        ex.reasons.map { it.ruleId } shouldNotContain ruleId
        ex.fullyExplained shouldBe false
        ex.status shouldBe ExplainStatus.DISCREPANCY
        ex.unexplainedCents shouldBe 400L
    }

    test("L-26 AC4: after a partial sale only the pro-rated commission left in the pool ACB counts") {
        val commission = 1_001L

        fun rows(c: Long) =
            listOf(
                buy("b1", "2025-02-03", 10, 100_000, 1, c),
                L18.sell("s1", sec, d("2025-03-03"), 4 * share, 200_000, 2),
            )
        val withCommission = rows(commission)
        val inPool = L17.cents(withCommission, key) - L17.cents(rows(0), key)
        (inPool in 1 until commission) shouldBe true
        val ex = L17.explain(withCommission, L17.cents(rows(0), key), key)
        val reason = ex.reasons.single { it.ruleId == ruleId }
        reason.amountMinor shouldBe inPool
        reason.entryIds shouldBe listOf(1L)
        ex.fullyExplained shouldBe true
    }

    test("L-26 AC5: any arrival order gives the same reasons in the same order") {
        val rows = threeBuys()
        val book = L17.cents(rows, key) - (300 + 700)
        val expected = reasonsOf(rows, book)
        expected.any { it.first == ruleId } shouldBe true
        reasonsOf(rows.reversed(), book) shouldBe expected
        (0 until 5).forEach { seed -> reasonsOf(rows.shuffled(kotlin.random.Random(seed)), book) shouldBe expected }
    }
})
