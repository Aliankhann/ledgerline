package dev.ledgerline.spec

import dev.ledgerline.app.explainFromActivities
import dev.ledgerline.calc.AcbEngine
import dev.ledgerline.calc.CaIta2026v1
import dev.ledgerline.calc.ExplainStatus
import dev.ledgerline.calc.Explanation
import dev.ledgerline.calc.HoldingExplanation
import dev.ledgerline.calc.PoolKey
import dev.ledgerline.calc.PostedActivity
import dev.ledgerline.core.AccountType
import dev.ledgerline.core.ActivityType
import dev.ledgerline.testsupport.Activities
import dev.ledgerline.testsupport.L06Activities
import dev.ledgerline.testsupport.L17.checkRunning
import dev.ledgerline.testsupport.L17.csvFor
import dev.ledgerline.testsupport.L17.explain
import dev.ledgerline.testsupport.L17.parse
import dev.ledgerline.testsupport.L17.text
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.longs.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import java.time.LocalDate
import kotlin.random.Random

/**
 * L-17 phase B: adversarial tests against `HoldingExplanation` and the explain route. Tax numbers are never asserted
 * here; every expectation is structural (sums, ordering, entry ids, status) or a plain restatement of story text.
 * Reversals and corrections are applied below `explain` (the route's source is `effectiveActivities`, which drops the
 * reversed pair), so the reversal-shaped test feeds `explain` the effective set that results from a reversed buy.
 */
class L17AttackSpec : FunSpec({

    val engine = AcbEngine(CaIta2026v1)
    val tp = L06Activities.TAXPAYER
    val share = Activities.SHARE

    fun trade(
        key: String,
        type: ActivityType,
        date: String,
        security: String,
        shares: Long,
        grossCents: Long,
        seq: Long,
        accountId: String = L06Activities.NR_A,
        accountType: AccountType = AccountType.NON_REGISTERED,
    ) = L06Activities.trade(
        key, type, LocalDate.parse(date), security, shares * share, grossCents, 0, seq, accountId, accountType,
    )

    /** Structural truths that must hold for any explanation with a book value. */
    fun checkStructure(e: Explanation) {
        e.steps.checkRunning()
        e.steps.sumOf { it.costDeltaCents } shouldBe e.acbCents
        e.differenceCents?.let { d ->
            e.reasons.sumOf { it.amountMinor } shouldBe d
            val stepEntries = e.steps.map { it.entryId }.toSet()
            e.reasons.filter { it.amountMinor != 0L && it.ruleId != "NO-LEDGER-HISTORY" && it.ruleId != "DATA_GAP" }
                .forEach { r -> r.entryIds.any { it in stepEntries } shouldBe true }
        }
    }

    // A loss sale whose only substituted purchase is BEFORE the sale (10 days), with the rest still held.
    val sec = "SYN-ATK"
    val key = PoolKey(tp, sec)
    val priorBuyRows =
        listOf(
            trade("atk-b1", ActivityType.BUY, "2025-01-02", sec, 100, 1_000_000, 1),
            trade("atk-b2", ActivityType.BUY, "2025-06-20", sec, 10, 100_000, 2),
            trade("atk-s1", ActivityType.SELL, "2025-07-01", sec, 100, 500_000, 3),
        )

    test("L-17 attack 1: an add-back whose target precedes the sale keeps the steps ordered by tax date (AC5)") {
        val sale = engine.replaySuperficial(priorBuyRows).sales.single()
        sale.denied shouldBeGreaterThan 0L // precondition: the scenario really is a superficial loss
        val e = explain(priorBuyRows, null, key)
        requireNotNull(e.steps.single { "SL-ADDBACK-TARGET" in it.ruleIds }.superficialLoss)
        e.steps.map { it.taxDate } shouldBe e.steps.map { it.taxDate }.sorted()
    }

    test("L-17 attack 2: loss sale in December, repurchase in January, later sale: sums exact, steps ordered") {
        val rows =
            listOf(
                trade("atk2-b1", ActivityType.BUY, "2024-03-01", sec, 100, 1_000_000, 1),
                trade("atk2-s1", ActivityType.SELL, "2024-12-20", sec, 100, 600_000, 2),
                trade("atk2-b2", ActivityType.BUY, "2025-01-10", sec, 100, 550_000, 3),
                trade("atk2-s2", ActivityType.SELL, "2025-03-01", sec, 40, 300_000, 4),
            )
        val plain = CaIta2026v1.toReportedCents(engine.replay(rows).pools.getValue(key).totalAcb)
        val e = explain(rows, plain, key)
        e.steps.filter { "SL-ADDBACK-TARGET" in it.ruleIds }.size shouldBe 1
        checkStructure(e)
        e.steps.map { it.taxDate } shouldBe e.steps.map { it.taxDate }.sorted()
    }

    test("L-17 attack 3: a repurchase in a TFSA never cites an entry that is not a timeline step") {
        val rows =
            listOf(
                trade("atk3-b1", ActivityType.BUY, "2025-01-02", sec, 100, 1_000_000, 1),
                trade("atk3-s1", ActivityType.SELL, "2025-07-01", sec, 100, 500_000, 2),
                trade("atk3-nr", ActivityType.BUY, "2025-07-10", sec, 10, 90_000, 3),
                trade(
                    "atk3-tfsa", ActivityType.BUY, "2025-07-12", sec, 10, 90_000, 4,
                    L06Activities.TFSA, AccountType.TFSA,
                ),
            )
        val e = explain(rows, null, key)
        val steps = e.steps.map { it.entryId }
        e.steps.mapNotNull { it.superficialLoss }.forEach { sl ->
            sl.targetEntryIds.shouldNotBeEmpty()
            steps shouldContainAll sl.targetEntryIds
        }
    }

    test("L-17 attack 4: sold to zero then rebought: running totals pass through zero and deltas still sum to ACB") {
        val rows =
            listOf(
                trade("atk4-b1", ActivityType.BUY, "2024-01-10", sec, 50, 500_000, 1),
                trade("atk4-s1", ActivityType.SELL, "2024-06-10", sec, 50, 700_000, 2),
                trade("atk4-b2", ActivityType.BUY, "2025-02-10", sec, 20, 260_000, 3),
            )
        val acb = CaIta2026v1.toReportedCents(engine.replaySuperficial(rows).pools.getValue(key).totalAcb)
        val e = explain(rows, acb, key)
        e.steps.map { it.runningSharesUnits } shouldBe listOf(50L * share, 0L, 20L * share)
        e.steps[1].runningAcbCents shouldBe 0L
        e.status shouldBe ExplainStatus.MATCHES
        checkStructure(e)
    }

    suspend fun rawGet(
        rows: List<PostedActivity>,
        csv: String,
        path: String,
    ): Pair<HttpStatusCode, String> {
        var out: Pair<HttpStatusCode, String>? = null
        testApplication {
            application { explainFromActivities({ t -> if (t == tp) rows else emptyList() }, csv) }
            val res = runCatching { client.get(path) }
            out = res.getOrNull()?.let { it.status to it.bodyAsText() }
                ?: (HttpStatusCode.InternalServerError to "EXCEPTION: ${res.exceptionOrNull()}")
        }
        return requireNotNull(out)
    }

    test("L-17 attack 5: security ids that need URL encoding round-trip through the route") {
        listOf(
            "SYN AAA" to "SYN%20AAA",
            "BRK.B" to "BRK.B",
            "A&B" to "A%26B",
            "SYN/AAA" to "SYN%2FAAA",
            "ÉCO" to "%C3%89CO",
        )
            .forEach { (id, encoded) ->
                val rows = listOf(trade("atk5-$encoded", ActivityType.BUY, "2025-01-02", id, 10, 100_000, 1))
                val (status, body) = rawGet(rows, csvFor(id, null), "/v1/holdings/$tp/$encoded/explain")
                withClue(id) {
                    status shouldBe HttpStatusCode.OK
                    parse(body).text("securityId") shouldBe id
                }
                val (miss, missBody) = rawGet(rows, csvFor(id, null), "/v1/holdings/$tp/${encoded}x/explain")
                miss shouldBe HttpStatusCode.NotFound
                missBody shouldContain tp
            }
    }

    test("L-17 attack 6: very large holdings explain without overflow and keep exact sums") {
        val big = 400_000_000_000_000L // cents: $4 trillion, x 10^4 internal units = 4e18 each, two fit in a Long
        val rows =
            listOf(
                trade("atk6-b1", ActivityType.BUY, "2025-01-02", sec, 1, big, 1),
                trade("atk6-b2", ActivityType.BUY, "2025-02-02", sec, 1, big, 2),
                trade("atk6-s1", ActivityType.SELL, "2025-03-02", sec, 1, big + 1, 3),
            )
        val acb = CaIta2026v1.toReportedCents(engine.replaySuperficial(rows).pools.getValue(key).totalAcb)
        val e = explain(rows, acb + 1, key)
        e.acbCents shouldBe big
        e.differenceCents shouldBe -1L
        checkStructure(e)
    }

    test("L-17 attack 7: a book value row for a security with no trades is explained, not 404, if the list shows it") {
        val rows = listOf(trade("atk7-b1", ActivityType.BUY, "2025-01-02", sec, 10, 100_000, 1))
        val csv = "security_id,book_value\n$sec,10.00\nSYN-NOTRADES,50.00\n"
        val listed =
            PoolKey(tp, "SYN-NOTRADES") in
                dev.ledgerline.calc.BookValueComparison.compareAll(
                    engine.replaySuperficial(rows).pools,
                    dev.ledgerline.calc.BookValueCsv.parse(tp, csv),
                ).map { it.key }
        listed shouldBe true // the ACB-vs-book list shows this holding with a DATA_GAP reason
        val (status, body) = rawGet(rows, csv, "/v1/holdings/$tp/SYN-NOTRADES/explain")
        withClue(body) { status shouldBe HttpStatusCode.OK }
    }

    test("L-17 attack 8: a malformed book value row for another security does not break this holding's explain") {
        val rows = listOf(trade("atk8-b1", ActivityType.BUY, "2025-01-02", sec, 10, 100_000, 1))
        listOf("SYN-OTHER,not-money", "SYN-OTHER,99999999999999999999.00", "SYN-OTHER,5.00\nSYN-OTHER,6.00").forEach {
                bad ->
            val (status, body) =
                rawGet(
                    rows,
                    "security_id,book_value\n$sec,10.00\n$bad\n",
                    "/v1/holdings/$tp/$sec/explain",
                )
            withClue("$bad -> $body") { (status.value in 200..499) shouldBe true }
        }
    }

    test("L-17 attack 9: a sell left behind by a reversed buy is a data gap with no steps, and the sum rule holds") {
        val sell = trade("atk9-s1", ActivityType.SELL, "2025-02-02", sec, 10, 120_000, 2)
        val effectiveAfterReversal = listOf(sell) // the reversed buy is dropped by effectiveActivities
        val e = HoldingExplanation.explain(effectiveAfterReversal, 100_000, key)
        requireNotNull(e)
        e.steps.map { it.entryId }.forEach { id -> id shouldBe sell.entryId } // no step cites the reversed buy
        e.reasons.flatMap { it.entryIds }.forEach { it shouldBe sell.entryId }
        e.reasons.sumOf { it.amountMinor } shouldBe e.differenceCents
        e.status shouldBe ExplainStatus.DATA_GAP
        e.reasons.map { it.ruleId } shouldContain "DATA-GAP-OVERSELL"
        // A difference that is only labelled "cannot be traced" is not "fully explained" (AC8 intent, CLAUDE.md honesty).
        withClue(e.toString()) { e.fullyExplained shouldBe false }
    }

    test("L-17 attack 10: ties on date and source sequence give byte-identical output in any arrival order") {
        val rows =
            (1..6).map { i ->
                L06Activities.posted(
                    Activities.activity(
                        "atk10-$i",
                        if (i % 3 == 0) ActivityType.SELL else ActivityType.BUY,
                        LocalDate.of(2025, 4, 1),
                        100_000L + i,
                        securityId = sec,
                        quantity = (if (i % 3 == 0) 1 else 5) * share,
                        sourceSeq = 0,
                    ),
                    900L + (7 - i),
                )
            }
        val base = explain(rows, 1L, key)
        base.steps.checkRunning()
        (1..25).forEach { seed -> explain(rows.shuffled(Random(seed)), 1L, key) shouldBe base }
        explain(rows.reversed(), 1L, key) shouldBe base
    }

    test("L-17 attack 11: no-trade holdings: book value 0 is a 200 data gap, no book value and no trades stays 404") {
        val rows = listOf(trade("atk11-b1", ActivityType.BUY, "2025-01-02", sec, 10, 100_000, 1))
        val none = "/v1/holdings/$tp/SYN-NONE/explain"
        val zero = rawGet(rows, "security_id,book_value\n$sec,10.00\nSYN-NONE,0.00\n", none)
        withClue(zero.second) { zero.first shouldBe HttpStatusCode.OK }
        val body = parse(zero.second)
        body.text("status") shouldBe "DATA_GAP"
        val e = HoldingExplanation.explain(rows, 0L, PoolKey(tp, "SYN-NONE"))
        requireNotNull(e)
        e.steps.size shouldBe 0
        e.differenceCents shouldBe 0L
        val unknown = rawGet(rows, "security_id,book_value\n$sec,10.00\n", none)
        unknown.first shouldBe HttpStatusCode.NotFound
    }

    test("L-17 attack 12: a malformed book value file is a 400 naming the bad value, for each failure kind") {
        val rows = listOf(trade("atk12-b1", ActivityType.BUY, "2025-01-02", sec, 10, 100_000, 1))
        mapOf(
            "SYN-OTHER,not-money" to "not-money",
            "SYN-OTHER,99999999999999999999.00" to "Bad book value file",
            "SYN-OTHER,5.00\nSYN-OTHER,6.00" to "Duplicate security SYN-OTHER",
        ).forEach { (bad, named) ->
            val csv = "security_id,book_value\n$sec,10.00\n$bad\n"
            val (status, body) = rawGet(rows, csv, "/v1/holdings/$tp/$sec/explain")
            withClue("$bad -> $body") {
                status shouldBe HttpStatusCode.BadRequest
                body shouldContain named
            }
        }
    }
})
