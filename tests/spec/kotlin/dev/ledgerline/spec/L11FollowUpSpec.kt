package dev.ledgerline.spec

import dev.ledgerline.app.acbVsBookFromActivities
import dev.ledgerline.app.acbVsBookModule
import dev.ledgerline.calc.AcbEngine
import dev.ledgerline.calc.AcbPool
import dev.ledgerline.calc.BookValueComparison
import dev.ledgerline.calc.CaIta2026v1
import dev.ledgerline.calc.PoolKey
import dev.ledgerline.calc.PostedActivity
import dev.ledgerline.core.ActivityType
import dev.ledgerline.testsupport.L06Activities
import dev.ledgerline.testsupport.L07Activities
import dev.ledgerline.testsupport.L07Fixture
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.time.LocalDate

/**
 * L-11 follow-ups A and B (docs/status.md, open questions). PROVISIONAL: owner confirmation pending.
 * OWNER-INPUT: C31 (A: list every pool, missing book value shown as such; B: live route uses `replaySuperficial`).
 *
 * No tax value is invented. Part A is arithmetic and traceability over `AcbEngine` pools (as in `L11BookValueSpec`).
 * Part B takes every expected number from the cited L-07 fixtures `acb-slr-example1` (adjustedcostbase.ca, Example #1)
 * and `acb-slr-example2` (same source, "Example #2"; its `source.url` and `source.heading` are in the fixture files),
 * or from `AcbEngine.replaySuperficial` itself, which is the path the reconciler uses.
 *
 * API contract (PROPOSED by QE; Dev may add members, not change these). Existing `BookValueComparison.compare`,
 * `HoldingDiff` and `acbVsBookModule` keep their behaviour and tests; follow-up A is additive:
 *
 * - Package `dev.ledgerline.calc`: `data class HoldingRow(key: PoolKey, bookValueCents: Long?, acbCents: Long,
 *   differenceCents: Long?, reasons: List<Reason>)` and
 *   `BookValueComparison.compareAll(pools: Map<PoolKey, AcbPool>, bookValues: Map<PoolKey, Long>): List<HoldingRow>`.
 *   One row per key in `pools` or `bookValues`, sorted by (taxpayerId, securityId).
 *   - key in both: same values and reasons as `compare` (differenceCents = acbCents - bookValueCents).
 *   - pool, no book value: `bookValueCents = null`, `differenceCents = null`, `acbCents = toReportedCents(totalAcb)`,
 *     exactly one reason: ruleId `NO_BROKER_VALUE` (not a rule-set ID), non-blank text, `amountMinor = 0`, entryIds
 *     from that pool's history.
 *   - book value, no pool: `acbCents = 0`, `differenceCents = -bookValueCents`, exactly one reason: ruleId `DATA_GAP`,
 *     non-blank text, empty entryIds, `amountMinor = differenceCents`.
 *   (`compare` may keep `NO-LEDGER-HISTORY` for its own callers; the route uses `compareAll`, so it says `DATA_GAP`.)
 * - Package `dev.ledgerline.app`: `acbVsBookModule` (both overloads) answers through `compareAll`, scoped to the
 *   requested taxpayer (pools of other taxpayers in the map never appear). For a known taxpayer (has a pool) every pool
 *   and every CSV row is listed; JSON `bookValueCents` and `differenceCents` are JSON `null` for a missing book value.
 *   A taxpayer with no pool still gets `holdings: []` (unchanged).
 * - Package `dev.ledgerline.app` (follow-up B): `fun Application.acbVsBookFromActivities(
 *   activities: (taxpayer: String) -> List<PostedActivity>, bookValueCsv: String)`: same route, but the pools are
 *   `AcbEngine(CaIta2026v1).replaySuperficial(activities(taxpayer)).pools`, so the superficial-loss reasons show.
 *   `Main.kt` must call it (with `ActivityStore.effectiveActivities`) instead of building pools with `engine.replay`.
 */
class L11FollowUpSpec : FunSpec({

    val engine = AcbEngine(CaIta2026v1)
    val tp1 = "TP-0001"
    val aaa = PoolKey(tp1, "SYN-AAA")
    val bbb = PoolKey(tp1, "SYN-BBB")
    val ccc = PoolKey(tp1, "SYN-CCC")

    fun day(
        m: Int,
        d: Int,
    ): LocalDate = LocalDate.of(2025, m, d)

    fun buy(
        key: String,
        sec: String,
        month: Int,
        shares: Long,
        gross: Long,
        seq: Long,
        taxpayer: String = tp1,
    ) = L06Activities.trade(key, ActivityType.BUY, day(month, 10), sec, shares, gross, 495, seq, taxpayerId = taxpayer)

    fun cents(pool: AcbPool): Long = CaIta2026v1.toReportedCents(pool.totalAcb)

    fun dollars(c: Long): String = "${c / 100}.${"%02d".format(c % 100)}"

    val pools =
        engine.replay(
            listOf(
                buy("A1", aaa.securityId, 3, 100_000_000L, 301_250, 1),
                buy("B1", bbb.securityId, 4, 50_000_000L, 225_000, 2),
                buy("C1", ccc.securityId, 5, 20_000_000L, 40_000, 3),
                L06Activities.roc("A2", day(6, 30), aaa.securityId, 1_200, 4),
            ),
        ).pools

    // ---------------------------------------------------------------- A, pure

    test("L-11 follow-up A: a pool with no book value row is listed with null book value and NO_BROKER_VALUE") {
        val rows = BookValueComparison.compareAll(pools, mapOf(aaa to 1_000L, bbb to 2_000L))
        val c = rows.single { it.key == ccc }
        c.bookValueCents shouldBe null
        c.differenceCents shouldBe null
        c.acbCents shouldBe cents(pools.getValue(ccc))
        val r = c.reasons.single()
        r.ruleId shouldBe "NO_BROKER_VALUE"
        r.text.isNotBlank() shouldBe true
        r.amountMinor shouldBe 0L
        val historyIds = pools.getValue(ccc).history.map { it.entryId }.toSet()
        r.entryIds.forEach { historyIds shouldContain it }
    }

    test("L-11 follow-up A: every security with a pool is listed, whatever the book value file holds") {
        BookValueComparison.compareAll(pools, emptyMap()).map { it.key } shouldBe listOf(aaa, bbb, ccc)
        BookValueComparison.compareAll(pools, emptyMap()).forEach {
            it.bookValueCents shouldBe null
            it.reasons.single().ruleId shouldBe "NO_BROKER_VALUE"
        }
    }

    test("L-11 follow-up A: a book value with no activity is a DATA_GAP row for the whole difference") {
        val ghost = PoolKey(tp1, "SYN-GHOST")
        val rows = BookValueComparison.compareAll(pools, mapOf(ghost to 5_000L))
        val g = rows.single { it.key == ghost }
        g.bookValueCents shouldBe 5_000L
        g.acbCents shouldBe 0L
        g.differenceCents shouldBe -5_000L
        val r = g.reasons.single()
        r.ruleId shouldBe "DATA_GAP"
        r.text.isNotBlank() shouldBe true
        r.entryIds.shouldBeEmpty()
        r.amountMinor shouldBe -5_000L
    }

    test("L-11 follow-up A: rows are sorted by taxpayer then security across pool-only and book-only keys") {
        val other = PoolKey("TP-0002", "SYN-AAA")
        val mixed =
            engine.replay(
                listOf(buy("X1", other.securityId, 3, 10_000_000L, 1_000, 9, taxpayer = "TP-0002")),
            ).pools
        val all = pools + mixed
        val book =
            mapOf(
                PoolKey(tp1, "SYN-BBBB") to 100L,
                PoolKey(tp1, "SYN-AAAA") to 100L,
                PoolKey("TP-0003", "SYN-A") to 100L,
            )
        BookValueComparison.compareAll(all, book).map { it.key.taxpayerId to it.key.securityId } shouldBe
            listOf(
                tp1 to "SYN-AAA", tp1 to "SYN-AAAA", tp1 to "SYN-BBB", tp1 to "SYN-BBBB", tp1 to "SYN-CCC",
                "TP-0002" to "SYN-AAA", "TP-0003" to "SYN-A",
            )
    }

    test("L-11 follow-up A: a key with both a pool and a book value is compared exactly as before") {
        val book = pools.mapValues { cents(it.value) + 777L }
        val old = BookValueComparison.compare(pools, book).associateBy { it.key }
        val rows = BookValueComparison.compareAll(pools, book)
        rows.map { it.key } shouldBe old.keys.sortedWith(compareBy({ it.taxpayerId }, { it.securityId }))
        rows.forEach {
            val o = old.getValue(it.key)
            it.bookValueCents shouldBe o.bookValueCents
            it.acbCents shouldBe o.acbCents
            it.differenceCents shouldBe o.differenceCents
            it.reasons shouldBe o.reasons
        }
    }

    test("L-11 follow-up A: no pools and no book values gives no rows") {
        BookValueComparison.compareAll(emptyMap(), emptyMap()).shouldBeEmpty()
    }

    // ---------------------------------------------------------------- A, route

    fun body(text: String): JsonObject = Json.parseToJsonElement(text).jsonObject

    fun JsonObject.holding(id: String): JsonObject {
        val all = getValue("holdings").jsonArray.map { it.jsonObject }
        return all.single { it.getValue("securityId").jsonPrimitive.content == id }
    }

    test(
        "L-11 follow-up A API: pools without a book value are null, CSV rows without activity are DATA_GAP",
    ) {
        val csv =
            "security_id,book_value\n" +
                "SYN-AAA,${dollars(cents(pools.getValue(aaa)))}\n" +
                "SYN-GHOST,50.00\n"
        val foreign = engine.replay(listOf(buy("X1", "SYN-ZZZ", 3, 10_000_000L, 1_000, 9, taxpayer = "TP-0002"))).pools
        testApplication {
            application { acbVsBookModule(pools + foreign, csv) }
            val res = client.get("/v1/holdings/$tp1/acb-vs-book")
            res.status shouldBe HttpStatusCode.OK
            val b = body(res.bodyAsText())
            val holdings: JsonArray = b.getValue("holdings").jsonArray
            holdings.map { it.jsonObject.getValue("securityId").jsonPrimitive.content } shouldBe
                listOf("SYN-AAA", "SYN-BBB", "SYN-CCC", "SYN-GHOST")

            val withBook = b.holding("SYN-AAA")
            withBook.getValue("differenceCents").jsonPrimitive.content.toLong() shouldBe 0L

            val noBook = b.holding("SYN-BBB")
            noBook.getValue("bookValueCents") shouldBe JsonNull
            noBook.getValue("differenceCents") shouldBe JsonNull
            noBook.getValue("acbCents").jsonPrimitive.content.toLong() shouldBe cents(pools.getValue(bbb))
            noBook.getValue("reasons").jsonArray.single().jsonObject.getValue("ruleId").jsonPrimitive.content shouldBe
                "NO_BROKER_VALUE"

            val gap = b.holding("SYN-GHOST")
            gap.getValue("bookValueCents").jsonPrimitive.content.toLong() shouldBe 5_000L
            gap.getValue("acbCents").jsonPrimitive.content.toLong() shouldBe 0L
            gap.getValue("differenceCents").jsonPrimitive.content.toLong() shouldBe -5_000L
            val gapReason = gap.getValue("reasons").jsonArray.single().jsonObject
            gapReason.getValue("ruleId").jsonPrimitive.content shouldBe "DATA_GAP"
            gapReason.getValue("amountMinor").jsonPrimitive.content.toLong() shouldBe -5_000L
            gapReason.getValue("entryIds").jsonArray.shouldBeEmpty()

            res.bodyAsText().contains("SYN-ZZZ") shouldBe false
        }
    }

    test("L-11 follow-up A API: with the default header-only book value file every pool is listed as NO_BROKER_VALUE") {
        testApplication {
            application { acbVsBookModule(pools, "security_id,book_value\n") }
            val b = body(client.get("/v1/holdings/$tp1/acb-vs-book").bodyAsText())
            val rows = b.getValue("holdings").jsonArray.map { it.jsonObject }
            rows.map { it.getValue("securityId").jsonPrimitive.content } shouldBe
                listOf("SYN-AAA", "SYN-BBB", "SYN-CCC")
            rows.forEach {
                it.getValue("bookValueCents") shouldBe JsonNull
                it.getValue("reasons").jsonArray.single().jsonObject.getValue("ruleId").jsonPrimitive.content shouldBe
                    "NO_BROKER_VALUE"
            }
        }
    }

    test("L-11 follow-up A API: a taxpayer with no pool still gets no holdings, even if the CSV has rows") {
        testApplication {
            application { acbVsBookModule(pools, "security_id,book_value\nSYN-GHOST,50.00\n") }
            val res = client.get("/v1/holdings/TP-9999/acb-vs-book")
            res.status shouldBe HttpStatusCode.OK
            res.bodyAsText() shouldContain "\"holdings\":[]"
        }
    }

    // ---------------------------------------------------------------- B

    val ex1 = L07Fixture.load("acb-slr-example1")
    val ex2 = L07Fixture.load("acb-slr-example2")
    val slKey = PoolKey(L06Activities.TAXPAYER, ex1.security)
    val ex1Rows: List<PostedActivity> =
        listOf("t1", "t2", "t3").mapIndexed { i, p -> L07Activities.row(ex1, p, seq = i + 1L) }

    // The broker book value ignores the superficial-loss add-back: it is the plain replay's ACB.
    val ex1Csv = "security_id,book_value\n${ex1.security},${dollars(
        cents(engine.replay(ex1Rows).pools.getValue(slKey)),
    )}\n"

    test(
        "L-11 follow-up B: the live route's ACB equals replaySuperficial's ACB (ACB-SLR #1) and not the plain replay's",
    ) {
        val expected = cents(engine.replaySuperficial(ex1Rows).pools.getValue(slKey))
        val plain = cents(engine.replay(ex1Rows).pools.getValue(slKey))
        (expected - plain) shouldBe ex1.long("expected.t2.denied.cents")
        expected shouldBe ex1.long("expected.afterT3.acb.cents")
        var asked: String? = null
        testApplication {
            application {
                acbVsBookFromActivities({ tp ->
                    asked = tp
                    ex1Rows
                }, ex1Csv)
            }
            val res = client.get("/v1/holdings/${L06Activities.TAXPAYER}/acb-vs-book")
            res.status shouldBe HttpStatusCode.OK
            asked shouldBe L06Activities.TAXPAYER
            val h = body(res.bodyAsText()).holding(ex1.security)
            h.getValue("acbCents").jsonPrimitive.content.toLong() shouldBe expected
            h.getValue("bookValueCents").jsonPrimitive.content.toLong() shouldBe plain
            h.getValue("differenceCents").jsonPrimitive.content.toLong() shouldBe expected - plain
        }
    }

    test("L-11 follow-up B: the live route includes the superficial-loss reason with its rule id and the sale entry") {
        val saleEntry = ex1Rows[1].entryId
        testApplication {
            application { acbVsBookFromActivities({ _ -> ex1Rows }, ex1Csv) }
            val h =
                body(
                    client.get("/v1/holdings/${L06Activities.TAXPAYER}/acb-vs-book").bodyAsText(),
                ).holding(ex1.security)
            val reasons = h.getValue("reasons").jsonArray.map { it.jsonObject }
            val sl = reasons.filter { it.getValue("ruleId").jsonPrimitive.content.startsWith("SL-") }
            sl.shouldNotBeEmpty()
            sl.forEach { CaIta2026v1.ruleIds shouldContain it.getValue("ruleId").jsonPrimitive.content }
            sl.sumOf { it.getValue("amountMinor").jsonPrimitive.content.toLong() } shouldBe
                ex1.long("expected.t2.denied.cents")
            sl.flatMap {
                    r ->
                r.getValue("entryIds").jsonArray.map { it.jsonPrimitive.content.toLong() }
            } shouldContain saleEntry
            reasons.sumOf { it.getValue("amountMinor").jsonPrimitive.content.toLong() } shouldBe
                h.getValue("differenceCents").jsonPrimitive.content.toLong()
        }
    }

    test("L-11 follow-up B: the activity source's order does not change the route's answer (canonical order)") {
        suspend fun answer(rows: List<PostedActivity>): String {
            var text = ""
            testApplication {
                application { acbVsBookFromActivities({ _ -> rows }, ex1Csv) }
                text = client.get("/v1/holdings/${L06Activities.TAXPAYER}/acb-vs-book").bodyAsText()
            }
            return text
        }
        answer(ex1Rows.reversed()) shouldBe answer(ex1Rows)
    }

    test("L-11 follow-up B: a repurchase in an RRSP never enters the pool, so the live ACB is 0 (ACB-SLR #2)") {
        val rows = listOf("t1", "t2", "t3", "t4").mapIndexed { i, p -> L07Activities.row(ex2, p, seq = i + 1L) }
        val key = PoolKey(L06Activities.TAXPAYER, ex2.security)
        val expected = cents(engine.replaySuperficial(rows).pools.getValue(key))
        expected shouldBe ex2.long("expected.afterT2.acb.cents")
        testApplication {
            application { acbVsBookFromActivities({ _ -> rows }, "security_id,book_value\n${ex2.security},10.00\n") }
            val h =
                body(
                    client.get("/v1/holdings/${L06Activities.TAXPAYER}/acb-vs-book").bodyAsText(),
                ).holding(ex2.security)
            h.getValue("acbCents").jsonPrimitive.content.toLong() shouldBe expected
            h.getValue("differenceCents").jsonPrimitive.content.toLong() shouldBe expected - 1_000L
        }
    }

    test("L-11 follow-up B: a taxpayer with no activities gets no holdings") {
        testApplication {
            application { acbVsBookFromActivities({ _ -> emptyList() }, ex1Csv) }
            val res = client.get("/v1/holdings/TP-9999/acb-vs-book")
            res.status shouldBe HttpStatusCode.OK
            res.bodyAsText() shouldContain "\"holdings\":[]"
        }
    }

    test("L-11 follow-up B: Main.kt wires the route through acbVsBookFromActivities, not through a plain replay") {
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        val rel = "app/src/main/kotlin/dev/ledgerline/app/Main.kt"
        while (dir != null && !File(dir, rel).exists()) dir = dir.parentFile
        val main = File(requireNotNull(dir) { "repo root not found" }, rel).readText()
        main shouldContain "acbVsBookFromActivities"
        (main.contains("engine.replay(")) shouldBe false
    }
})
