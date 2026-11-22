package dev.ledgerline.spec

import dev.ledgerline.app.acbVsBookFromActivities
import dev.ledgerline.calc.AcbEngine
import dev.ledgerline.calc.BookValueComparison
import dev.ledgerline.calc.CaIta2026v1
import dev.ledgerline.calc.PoolKey
import dev.ledgerline.calc.PostedActivity
import dev.ledgerline.calc.Reconciler
import dev.ledgerline.core.ActivityType
import dev.ledgerline.testsupport.L06Activities
import dev.ledgerline.testsupport.L07Activities
import dev.ledgerline.testsupport.L07Fixture
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.time.LocalDate

/**
 * L-11 follow-up, phase B attack. No tax value is invented: ACB-SLR Example #1 supplies the superficial loss numbers
 * (`acb-slr-example1.properties`); every other check is an equality between two code paths or an arithmetic identity
 * (reasons sum to the difference), so none needs a published expected value.
 */
class L11FollowUpAttackSpec : FunSpec({

    val engine = AcbEngine(CaIta2026v1)
    val tp1 = L06Activities.TAXPAYER
    val tp2 = "TP-0002"

    fun day(
        m: Int,
        d: Int,
    ): LocalDate = LocalDate.of(2025, m, d)

    fun trade(
        key: String,
        type: ActivityType,
        sec: String,
        m: Int,
        d: Int,
        shares: Long,
        gross: Long,
        seq: Long,
        taxpayer: String = tp1,
    ): PostedActivity = L06Activities.trade(key, type, day(m, d), sec, shares, gross, 0, seq, taxpayerId = taxpayer)

    fun obj(text: String): JsonObject = Json.parseToJsonElement(text).jsonObject

    suspend fun get(
        rows: (String) -> List<PostedActivity>,
        csv: String,
        taxpayer: String,
    ): Pair<HttpStatusCode, String> {
        var out: Pair<HttpStatusCode, String>? = null
        testApplication {
            application { acbVsBookFromActivities(rows, csv) }
            val res = client.get("/v1/holdings/$taxpayer/acb-vs-book")
            out = res.status to res.bodyAsText()
        }
        return checkNotNull(out)
    }

    // ------------------------------------------------------------------ taxpayer scoping

    test("L-11 follow-up attack: an activity source that returns another taxpayer's rows leaks nothing") {
        val mixed =
            listOf(
                trade("S1", ActivityType.BUY, "SYN-MINE", 3, 10, 10_000_000L, 1_000, 1),
                trade("S2", ActivityType.BUY, "SYN-THEIRS", 3, 10, 10_000_000L, 1_000, 2, taxpayer = tp2),
            )
        val (status, text) = get({ _ -> mixed }, "security_id,book_value\n", tp1)
        status shouldBe HttpStatusCode.OK
        text shouldContain "SYN-MINE"
        text shouldNotContain "SYN-THEIRS"
        text shouldNotContain tp2
    }

    test(
        "L-11 follow-up attack: a security held only by another taxpayer is unknown to this one, even with a CSV row",
    ) {
        val rows = listOf(trade("S3", ActivityType.BUY, "SYN-THEIRS", 3, 10, 10_000_000L, 1_000, 1, taxpayer = tp2))
        val (status, text) = get({ _ -> rows }, "security_id,book_value\nSYN-THEIRS,10.00\n", tp1)
        status shouldBe HttpStatusCode.OK
        text shouldContain "\"holdings\":[]"
    }

    // ------------------------------------------------------------------ zero-share pools

    val sold =
        engine.replay(
            listOf(
                trade("Z1", ActivityType.BUY, "SYN-SOLD", 3, 10, 100_000_000L, 301_250, 1),
                trade("Z2", ActivityType.SELL, "SYN-SOLD", 4, 10, 100_000_000L, 400_000, 2),
            ),
        ).pools
    val soldKey = PoolKey(tp1, "SYN-SOLD")

    test("L-11 follow-up attack: a fully sold pool with no book row is listed with ACB 0 and NO_BROKER_VALUE") {
        sold.getValue(soldKey).shares shouldBe 0L
        val row = BookValueComparison.compareAll(sold, emptyMap()).single()
        row.acbCents shouldBe 0L
        row.bookValueCents shouldBe null
        row.differenceCents shouldBe null
        row.reasons.single().ruleId shouldBe "NO_BROKER_VALUE"
        row.reasons.single().entryIds shouldBe sold.getValue(soldKey).history.map { it.entryId }
    }

    test("L-11 follow-up attack: a fully sold pool with book value 0 has no difference and no reasons") {
        val row = BookValueComparison.compareAll(sold, mapOf(soldKey to 0L)).single()
        row.differenceCents shouldBe 0L
        row.reasons.shouldBeEmpty()
    }

    // ------------------------------------------------------------------ reasons sum to the difference

    val ex1 = L07Fixture.load("acb-slr-example1")
    val ex1Rows = listOf("t1", "t2", "t3").mapIndexed { i, p -> L07Activities.row(ex1, p, seq = i + 1L) }
    val ex1Key = PoolKey(tp1, ex1.security)
    val slPools = engine.replaySuperficial(ex1Rows).pools
    val slAcb = CaIta2026v1.toReportedCents(slPools.getValue(ex1Key).totalAcb)

    test("L-11 follow-up attack: for any book value the reasons of a compared row sum to its difference") {
        val books =
            listOf(0L, 1L, slAcb - 1, slAcb, slAcb + 1, slAcb - ex1.long("expected.t2.denied.cents"), 10L * slAcb)
        for (book in books) {
            val row = BookValueComparison.compareAll(slPools, mapOf(ex1Key to book)).single()
            row.differenceCents shouldBe slAcb - book
            row.reasons.sumOf { it.amountMinor } shouldBe slAcb - book
        }
        for (book in listOf(0L, 5_000L)) {
            val row = BookValueComparison.compareAll(sold, mapOf(soldKey to book)).single()
            row.reasons.sumOf { it.amountMinor } shouldBe -book
        }
    }

    // ------------------------------------------------------------------ duplicate and extreme CSV rows

    // The test host rethrows an unhandled route exception; the live Netty server turns it into an empty 500.
    // Either way the request is refused, which is all these two tests ask for.
    suspend fun refused(csv: String): Boolean {
        val outcome = runCatching { get({ _ -> ex1Rows }, csv, tp1) }
        val result = outcome.getOrNull()
        return result == null || result.first != HttpStatusCode.OK
    }

    test("L-11 follow-up attack: duplicate CSV rows for one security are refused, never last-wins") {
        refused("security_id,book_value\n${ex1.security},10.00\n${ex1.security},20.00\n") shouldBe true
    }

    test("L-11 follow-up attack: a book value that overflows Long is refused, not wrapped") {
        refused("security_id,book_value\n${ex1.security},92233720368547758.08\n") shouldBe true
    }

    test("L-11 follow-up attack: the largest representable book value gives a row, not an overflow") {
        val csv = "security_id,book_value\n${ex1.security},92233720368547758.07\n"
        val (status, text) = get({ _ -> ex1Rows }, csv, tp1)
        status shouldBe HttpStatusCode.OK
        val h = obj(text).getValue("holdings").jsonArray.single().jsonObject
        h.getValue("bookValueCents").jsonPrimitive.content.toLong() shouldBe Long.MAX_VALUE
        h.getValue("differenceCents").jsonPrimitive.content.toLong() shouldBe slAcb - Long.MAX_VALUE
    }

    // ------------------------------------------------------------------ JSON null versus omitted

    test("L-11 follow-up attack: a missing book value is a present JSON null, acbCents is never null") {
        val rows = listOf(trade("N1", ActivityType.BUY, "SYN-NB", 3, 10, 10_000_000L, 1_000, 1))
        val (_, text) = get({ _ -> rows }, "security_id,book_value\nSYN-GHOST,1.00\n", tp1)
        text shouldContain "\"bookValueCents\":null"
        text shouldContain "\"differenceCents\":null"
        for (h in obj(text).getValue("holdings").jsonArray.map { it.jsonObject }) {
            h.keys shouldBe setOf("securityId", "bookValueCents", "acbCents", "differenceCents", "reasons")
            h.getValue("acbCents") shouldNotBe JsonNull
        }
        val gap =
            obj(text).getValue("holdings").jsonArray.map { it.jsonObject }.single {
                it.getValue("securityId").jsonPrimitive.content == "SYN-GHOST"
            }
        gap.getValue("differenceCents") shouldNotBe JsonNull
    }

    // ------------------------------------------------------------------ sort stability

    test("L-11 follow-up attack: the order of map entries and CSV rows does not change the rows") {
        val ids = listOf("SYN-b", "SYN-B", "SYN-a", "SYN-A", "SYN-10", "SYN-9", "SYN-é", "SYN-Z")
        val acts = ids.mapIndexed { i, id -> trade("O$i", ActivityType.BUY, id, 3, 10, 10_000_000L, 1_000, i + 1L) }
        val pools = engine.replay(acts).pools
        val book = ids.take(5).associate { PoolKey(tp1, it) to 500L }
        val expected = BookValueComparison.compareAll(pools, book)
        expected.map { it.key.securityId } shouldBe ids.sorted()
        repeat(10) { seed ->
            val rnd = java.util.Random(seed.toLong())
            val p = pools.entries.shuffled(rnd).associate { it.key to it.value }
            val b = book.entries.shuffled(rnd).associate { it.key to it.value }
            BookValueComparison.compareAll(p, b) shouldBe expected
        }
        val csvA = "security_id,book_value\n" + ids.take(5).joinToString("") { "$it,5.00\n" }
        val csvB = "security_id,book_value\n" + ids.take(5).reversed().joinToString("") { "$it,5.00\n" }
        val a = get({ _ -> acts }, csvA, tp1).second
        val b = get({ _ -> acts.reversed() }, csvB, tp1).second
        a shouldBe b
    }

    // ------------------------------------------------------------------ route versus reconciler

    test("L-11 follow-up attack: with no superficial loss in play, the route's ACB equals the reconciler's replay") {
        val acts =
            listOf(
                trade("R1", ActivityType.BUY, "SYN-RR", 1, 10, 100_000_000L, 301_250, 1),
                trade("R2", ActivityType.BUY, "SYN-RR", 2, 10, 50_000_000L, 200_000, 2),
                trade("R3", ActivityType.SELL, "SYN-RR", 5, 10, 70_000_000L, 350_000, 3),
                L06Activities.roc("R4", day(6, 30), "SYN-RR", 1_200, 4),
            )
        val slPoolsHere = engine.replaySuperficial(acts).pools
        val report = Reconciler(engine).reconcile(slPoolsHere, acts)
        report.drift.shouldBeEmpty()
        report.ok shouldBe true
        val replayAcb = CaIta2026v1.toReportedCents(engine.replay(acts).pools.getValue(PoolKey(tp1, "SYN-RR")).totalAcb)
        val (_, text) = get({ _ -> acts }, "security_id,book_value\n", tp1)
        val h = obj(text).getValue("holdings").jsonArray.single().jsonObject
        h.getValue("acbCents").jsonPrimitive.content.toLong() shouldBe replayAcb
    }
})
