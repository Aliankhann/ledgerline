package dev.ledgerline.spec

import dev.ledgerline.app.acbVsBookModule
import dev.ledgerline.calc.AcbEngine
import dev.ledgerline.calc.AcbPool
import dev.ledgerline.calc.BookValueComparison
import dev.ledgerline.calc.BookValueCsv
import dev.ledgerline.calc.CaIta2026v1
import dev.ledgerline.calc.PoolKey
import dev.ledgerline.core.ActivityType
import dev.ledgerline.testsupport.L06Activities
import io.kotest.assertions.throwables.shouldThrow
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
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.time.LocalDate

/**
 * Story L-11 (docs/stories/L-11-book-value-vs-acb-view.md), backend only; PROVISIONAL (owner input B2 pending).
 * The UI criteria (AC1 to AC5, AC7) are in the Test map as a follow-up; no JS runner is added.
 *
 * No tax value is asserted. Oracle: the book value is built from `AcbEngine` pools (for example the ACB before a
 * return of capital), and the checks are arithmetic and traceability of the diff.
 *
 * API contract (PROPOSED by QE; Dev may add members, not change these). Package `dev.ledgerline.calc` unless noted.
 *
 * - `object BookValueComparison` with
 *   `fun compare(pools: Map<PoolKey, AcbPool>, bookValues: Map<PoolKey, Long>): List<HoldingDiff>`.
 *   Uses rule set `CaIta2026v1`. One row per pool with shares > 0 or history, sorted by (taxpayerId, securityId), zero
 *   differences included. Book values are cents; `acbCents` is `toReportedCents(pool.totalAcb)` (the single rounding
 *   point). A pool with no book value entry is not compared (no row is invented); a book value with no pool (provisional C29)
 *   becomes a row with `acbCents = 0` and exactly one reason, ruleId `NO-LEDGER-HISTORY` (not a rule-set ID), non-blank
 *   text, empty `entryIds`, whose `amountMinor` is the whole difference.
 * - `data class HoldingDiff(key: PoolKey, bookValueCents: Long, acbCents: Long, differenceCents: Long,
 *   reasons: List<Reason>)` with `differenceCents = acbCents - bookValueCents` (story AC6 sign).
 * - `data class Reason(ruleId: String, text: String, entryIds: List<Long>, amountMinor: Long)`. A zero difference has no reasons. Every
 *   non-zero difference has at least one reason; each names a rule ID in `CaIta2026v1.ruleIds`, non-blank text, and at
 *   least one entry ID taken from that pool's `history`. The `amountMinor` of the reasons sum exactly to
 *   `differenceCents` (story AC6), for every holding including zero differences (empty sum). A return of capital in the history is explained by
 *   `ROC-REDUCES-ACB` naming the ROC entry.
 * - `object BookValueCsv` with `fun parse(taxpayerId: String, csv: String): Map<PoolKey, Long>`. PROVISIONAL format
 *   until owner input B2: header `security_id,book_value`, then rows with book value in dollars, an optional
 *   two-decimal fraction, no thousands separators, no sign. Malformed input throws `IllegalArgumentException`.
 * - Package `dev.ledgerline.app`: `fun Application.acbVsBookModule(pools: Map<PoolKey, AcbPool>, bookValueCsv: String)`
 *   installs JSON content negotiation and `GET /v1/holdings/{taxpayer}/acb-vs-book`, answering 200 with
 *   `{"taxpayerId", "currency":"CAD", "holdings":[{"securityId","bookValueCents","acbCents","differenceCents",
 *   "reasons":[{"ruleId","text","entryIds":[...],"amountMinor"}]}]}` for that taxpayer only; an unknown taxpayer gets `holdings: []`.
 */
class L11BookValueSpec : FunSpec({

    val engine = AcbEngine(CaIta2026v1)
    val tp1 = "TP-0001"
    val aaa = PoolKey(tp1, "SYN-AAA")
    val bbb = PoolKey(tp1, "SYN-BBB")

    fun day(
        m: Int,
        d: Int,
    ): LocalDate = LocalDate.of(2025, m, d)

    val buyA = L06Activities.trade("A1", ActivityType.BUY, day(3, 10), aaa.securityId, 100_000_000L, 301_250, 495, 1)
    val buyB = L06Activities.trade("B1", ActivityType.BUY, day(4, 14), bbb.securityId, 50_000_000L, 225_000, 495, 2)
    val rocA = L06Activities.roc("A2", day(6, 30), aaa.securityId, 1_200, 3)

    fun cents(pool: AcbPool): Long = CaIta2026v1.toReportedCents(pool.totalAcb)

    val beforeRoc = engine.replay(listOf(buyA, buyB)).pools
    val afterRoc = engine.replay(listOf(buyA, buyB, rocA)).pools
    val equalBook = afterRoc.mapValues { cents(it.value) }

    test("L-11 AC6 backend: book value equal to ACB gives a zero difference and no reasons for every holding") {
        val diffs = BookValueComparison.compare(afterRoc, equalBook)
        diffs.map { it.key } shouldBe listOf(aaa, bbb)
        diffs.forEach {
            it.differenceCents shouldBe 0L
            it.reasons.shouldBeEmpty()
            it.acbCents shouldBe it.bookValueCents
        }
    }

    test(
        "L-11 AC2/AC6 backend: ignoring a return of capital is explained by ROC-REDUCES-ACB",
    ) {
        val book = mapOf(aaa to cents(beforeRoc.getValue(aaa)), bbb to cents(beforeRoc.getValue(bbb)))
        val diffs = BookValueComparison.compare(afterRoc, book)
        val a = diffs.single { it.key == aaa }
        a.differenceCents shouldBe a.acbCents - a.bookValueCents
        a.differenceCents shouldBe cents(afterRoc.getValue(aaa)) - cents(beforeRoc.getValue(aaa))
        (a.differenceCents != 0L) shouldBe true
        a.reasons.shouldNotBeEmpty()
        a.reasons.sumOf { it.amountMinor } shouldBe a.differenceCents
        val roc = a.reasons.first { it.ruleId == "ROC-REDUCES-ACB" }
        roc.entryIds shouldContain rocA.entryId
        diffs.single { it.key == bbb }.reasons.shouldBeEmpty()
    }

    test("L-11 AC6 backend: every reason names a known rule, has text and entry IDs from the pool history") {
        val book = equalBook.mapValues { it.value + 777L }
        BookValueComparison.compare(afterRoc, book).forEach { diff ->
            diff.differenceCents shouldBe -777L
            diff.reasons.shouldNotBeEmpty()
            diff.reasons.sumOf { it.amountMinor } shouldBe diff.differenceCents
            val historyIds = afterRoc.getValue(diff.key).history.map { it.entryId }.toSet()
            diff.reasons.forEach { r ->
                CaIta2026v1.ruleIds shouldContain r.ruleId
                r.text.isNotBlank() shouldBe true
                r.entryIds.shouldNotBeEmpty()
                r.entryIds.forEach { historyIds shouldContain it }
            }
        }
    }

    test("L-11 AC6 backend: a book value above ACB gives a negative difference") {
        val d =
            BookValueComparison.compare(
                afterRoc,
                equalBook + (bbb to equalBook.getValue(bbb) + 1),
            ).single { it.key == bbb }
        d.differenceCents shouldBe -1L
        d.reasons.shouldNotBeEmpty()
    }

    test("L-11 AC6 backend: a book value with no pool is a NO-LEDGER-HISTORY row for the whole difference") {
        val ghost = PoolKey(tp1, "SYN-GHOST")
        val d = BookValueComparison.compare(afterRoc, equalBook + (ghost to 5_000L)).single { it.key == ghost }
        d.acbCents shouldBe 0L
        d.bookValueCents shouldBe 5_000L
        d.differenceCents shouldBe -5_000L
        val r = d.reasons.single()
        r.ruleId shouldBe "NO-LEDGER-HISTORY"
        r.amountMinor shouldBe -5_000L
        r.text.isNotBlank() shouldBe true
    }

    test("L-11 CSV: parses the provisional security_id,book_value format to cents without floating point") {
        val csv = "security_id,book_value\nSYN-AAA,1807.50\nSYN-BBB,2254.95\nSYN-CCC,12\n"
        BookValueCsv.parse(tp1, csv) shouldBe
            mapOf(aaa to 180_750L, bbb to 225_495L, PoolKey(tp1, "SYN-CCC") to 1_200L)
    }

    test("L-11 CSV: malformed input is rejected") {
        listOf(
            "wrong,header\nSYN-AAA,1.00\n",
            "security_id,book_value\nSYN-AAA,abc\n",
            "security_id,book_value\nSYN-AAA,1.005\n",
            "security_id,book_value\nSYN-AAA,-1.00\n",
            "security_id,book_value\nSYN-AAA\n",
            "security_id,book_value\nSYN-AAA,1.00\nSYN-AAA,2.00\n",
        ).forEach { shouldThrow<IllegalArgumentException> { BookValueCsv.parse(tp1, it) } }
    }

    test("L-11 API: GET /v1/holdings/{taxpayer}/acb-vs-book returns the diff as JSON, scoped to the taxpayer") {
        val other =
            L06Activities.trade(
                "X1", ActivityType.BUY,
                day(
                    3,
                    10,
                ),
                "SYN-AAA", 10_000_000L, 1_000, 0, 9, taxpayerId = "TP-0002",
            )
        val pools = engine.replay(listOf(buyA, buyB, rocA, other)).pools

        fun dollars(c: Long): String = "${c / 100}.${"%02d".format(c % 100)}"
        val csv =
            "security_id,book_value\n" +
                "SYN-AAA,${dollars(cents(beforeRoc.getValue(aaa)))}\n" +
                "SYN-BBB,${dollars(equalBook.getValue(bbb))}\n"
        testApplication {
            application { acbVsBookModule(pools, csv) }
            val res = client.get("/v1/holdings/$tp1/acb-vs-book")
            res.status shouldBe HttpStatusCode.OK
            val body: JsonObject = Json.parseToJsonElement(res.bodyAsText()).jsonObject
            body.getValue("taxpayerId").jsonPrimitive.content shouldBe tp1
            body.getValue("currency").jsonPrimitive.content shouldBe "CAD"
            val holdings: JsonArray = body.getValue("holdings").jsonArray
            val ids = holdings.map { it.jsonObject.getValue("securityId").jsonPrimitive.content }
            ids shouldBe listOf("SYN-AAA", "SYN-BBB")
            val a = holdings[0].jsonObject
            val book = a.getValue("bookValueCents").jsonPrimitive.content.toLong()
            val acb = a.getValue("acbCents").jsonPrimitive.content.toLong()
            a.getValue("differenceCents").jsonPrimitive.content.toLong() shouldBe acb - book
            val reasons = a.getValue("reasons").jsonArray
            reasons.shouldNotBeEmpty()
            reasons.forEach {
                CaIta2026v1.ruleIds shouldContain it.jsonObject.getValue("ruleId").jsonPrimitive.content
                it.jsonObject.getValue("entryIds").jsonArray.shouldNotBeEmpty()
            }
            reasons.sumOf { it.jsonObject.getValue("amountMinor").jsonPrimitive.content.toLong() } shouldBe
                a
                    .getValue("differenceCents")
                    .jsonPrimitive.content
                    .toLong()
            holdings[1].jsonObject.getValue("reasons").jsonArray.shouldBeEmpty()

            val empty = client.get("/v1/holdings/TP-9999/acb-vs-book")
            empty.status shouldBe HttpStatusCode.OK
            empty.bodyAsText() shouldContain "\"holdings\":[]"
        }
    }
})
