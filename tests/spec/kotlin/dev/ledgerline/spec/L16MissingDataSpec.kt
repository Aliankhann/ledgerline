package dev.ledgerline.spec

import dev.ledgerline.app.acbVsBookFromActivities
import dev.ledgerline.calc.AcbEngine
import dev.ledgerline.calc.BookValueComparison
import dev.ledgerline.calc.CaIta2026v1
import dev.ledgerline.calc.DataGapWarnings
import dev.ledgerline.calc.HoldingRow
import dev.ledgerline.calc.PoolKey
import dev.ledgerline.calc.PostedActivity
import dev.ledgerline.calc.Reason
import dev.ledgerline.core.ActivityType
import dev.ledgerline.testsupport.Activities
import dev.ledgerline.testsupport.L06Activities
import dev.ledgerline.testsupport.SourceText
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.time.LocalDate

/**
 * L-16 missing-data warnings (docs/stories/L-16-missing-data-warnings.md). PROVISIONAL: the story is a Draft and the
 * sub-rule ids are OWNER-INPUT placeholders. No tax value appears here: warnings carry `amountMinor` 0, and the only
 * numbers are the story's own inputs (cash in cents, shares in 10^-6 units via [Activities.SHARE]).
 *
 * API contract (PROPOSED by QE; Dev may add members, not change these):
 *
 * - `dev.ledgerline.calc.DataGapWarnings.detect(activities: List<PostedActivity>, replay: Replay):
 *   Map<PoolKey, List<Reason>>`. Pure. Keys are the taxpayer and security of the affected activities. Each warning is
 *   a `Reason(ruleId, text, entryIds, amountMinor = 0)`. `ruleId` is `DATA-GAP-TRANSFER-NO-COST` (text "shares arrived
 *   with no cost basis in the file", entryIds = the TRANSFER_IN entries) or `DATA-GAP-OVERSELL` (text "a sale exceeds
 *   the shares the file shows as held", entryIds = the rejected sell entries, found by mapping `Rejection.idempotencyKey`
 *   to the posted activity). Order independent: the answer does not depend on the order of [activities].
 * - `BookValueComparison.compareAll(pools, bookValues, warnings: Map<PoolKey, List<Reason>> = emptyMap())`. The third
 *   parameter MUST have a default so the existing two-argument callers (L-11 tests) still compile. Warnings are
 *   appended after the existing reasons of the row with the same key (pool-only, book-only and both).
 * - `acbVsBookFromActivities` (app) computes `DataGapWarnings.detect` from the same activities and passes it to
 *   `compareAll`, so the JSON `reasons` carry the warnings.
 */
class L16MissingDataSpec : FunSpec({

    val engine = AcbEngine(CaIta2026v1)
    val tp = Activities.TAXPAYER
    val xyz = PoolKey(tp, "XYZ")
    val share = Activities.SHARE

    fun day(d: Int): LocalDate = LocalDate.of(2025, 1, d)

    fun transferIn(
        entry: Long,
        shares: Long,
        date: LocalDate = day(10),
    ): PostedActivity =
        L06Activities.posted(
            Activities.activity(
                key = "k$entry",
                type = ActivityType.TRANSFER_IN,
                date = date,
                amountMinor = 0L,
                securityId = "XYZ",
                quantity = shares * share,
                sourceSeq = entry,
            ),
            entry,
        )

    fun trade(
        entry: Long,
        type: ActivityType,
        shares: Long,
        amountCents: Long,
        date: LocalDate,
    ): PostedActivity =
        L06Activities.posted(
            Activities.activity(
                key = "k$entry",
                type = type,
                date = date,
                amountMinor = amountCents,
                securityId = "XYZ",
                quantity = shares * share,
                sourceSeq = entry,
            ),
            entry,
        )

    fun buy(
        entry: Long,
        shares: Long,
        amountCents: Long,
        date: LocalDate = day(10),
    ) = trade(entry, ActivityType.BUY, shares, amountCents, date)

    fun sell(
        entry: Long,
        shares: Long,
        amountCents: Long,
        date: LocalDate = day(11),
    ) = trade(entry, ActivityType.SELL, shares, amountCents, date)

    /** What the service does: replay, detect warnings, compare. */
    fun rows(
        acts: List<PostedActivity>,
        book: Map<PoolKey, Long>,
    ): List<HoldingRow> {
        val replay = engine.replay(acts)
        return BookValueComparison.compareAll(replay.pools, book, DataGapWarnings.detect(acts, replay))
    }

    fun plainRows(
        acts: List<PostedActivity>,
        book: Map<PoolKey, Long>,
    ): List<HoldingRow> = BookValueComparison.compareAll(engine.replay(acts).pools, book)

    fun List<Reason>.gaps(): List<Reason> = filter { it.ruleId.startsWith("DATA-GAP-") }

    val noCost = "DATA-GAP-TRANSFER-NO-COST"
    val oversell = "DATA-GAP-OVERSELL"
    val noCostText = "shares arrived with no cost basis in the file"
    val oversellText = "a sale exceeds the shares the file shows as held"

    // AC1 input: entry 1 TRANSFER_IN 100 XYZ; entry 2 BUY 10 XYZ for $100.00; broker book value $1,100.00.
    val ac1 = listOf(transferIn(1, 100), buy(2, 10, 10_000))
    val ac1Book = mapOf(xyz to 110_000L)

    test("AC1 share-in with no cost: the XYZ row has DATA-GAP-TRANSFER-NO-COST on entry 1 with amount 0") {
        val row = rows(ac1, ac1Book).single { it.key == xyz }
        val w = row.reasons.gaps().single()
        w shouldBe Reason(noCost, noCostText, listOf(1L), 0L)
        // Pool rules do not change: the transfer is not in the pool, so ACB is the $100.00 buy alone.
        row.acbCents shouldBe 10_000L
        row.differenceCents shouldBe 10_000L - 110_000L
        // Other reasons, if any, are unchanged.
        row.reasons.filterNot { it.ruleId.startsWith("DATA-GAP-") } shouldBe
            plainRows(ac1, ac1Book).single { it.key == xyz }.reasons
    }

    test("AC2 sale exceeds shares held: a sell with no earlier buy is DATA-GAP-OVERSELL on entry 1, pool unchanged") {
        val acts = listOf(sell(1, 5, 5_000, day(10)), buy(2, 10, 10_000, day(11)))
        val replay = engine.replay(acts)
        replay.rejections.size shouldBe 1
        val row = rows(acts, mapOf(xyz to 10_000L)).single { it.key == xyz }
        row.reasons.gaps().single() shouldBe Reason(oversell, oversellText, listOf(1L), 0L)
        // The rejected sell changes nothing: 10 shares and $100.00 remain, exactly as the buy alone.
        row.acbCents shouldBe 10_000L
        replay.pools.getValue(xyz).shares shouldBe 10L * share
        row.differenceCents shouldBe 0L
    }

    test("AC2 order independence: warnings do not depend on the order the activities arrive in") {
        val acts = listOf(sell(1, 5, 5_000, day(10)), buy(2, 10, 10_000, day(11)))
        val forward = DataGapWarnings.detect(acts, engine.replay(acts))
        val reversed = acts.reversed()
        DataGapWarnings.detect(reversed, engine.replay(reversed)) shouldBe forward
        forward.getValue(xyz).single().entryIds shouldBe listOf(1L)
    }

    test("AC3 over-sell after a buy: selling 12 of 10 gives DATA-GAP-OVERSELL on entry 2; selling 10 gives none") {
        val over = listOf(buy(1, 10, 10_000), sell(2, 12, 13_000))
        val row = rows(over, mapOf(xyz to 10_000L)).single { it.key == xyz }
        row.reasons.gaps().single() shouldBe Reason(oversell, oversellText, listOf(2L), 0L)

        val exact = listOf(buy(1, 10, 10_000), sell(2, 10, 11_000))
        val ok = rows(exact, mapOf(xyz to 0L)).single { it.key == xyz }
        ok.reasons.filter { it.ruleId.startsWith("DATA") }.shouldBeEmpty()
    }

    test("AC4 clean history has no warning: buy 10 then sell 4 gives no DATA-GAP reason") {
        val acts = listOf(buy(1, 10, 10_000), sell(2, 4, 4_500))
        DataGapWarnings.detect(acts, engine.replay(acts)).values.flatten().shouldBeEmpty()
        val pool = engine.replay(acts).pools.getValue(xyz)
        val book = CaIta2026v1.toReportedCents(pool.totalAcb)
        rows(acts, mapOf(xyz to book + 1L)).single { it.key == xyz }.reasons.gaps().shouldBeEmpty()
        rows(acts, mapOf(xyz to book)).single { it.key == xyz }.reasons.gaps().shouldBeEmpty()
    }

    test("AC5 book value with no ledger activity is unchanged: DATA_GAP, no entry ids, amount is the difference") {
        val abc = PoolKey(tp, "ABC")
        val row = rows(emptyList(), mapOf(abc to 5_000L)).single()
        row.key shouldBe abc
        row.differenceCents shouldBe -5_000L
        val r = row.reasons.single()
        r.ruleId shouldBe "DATA_GAP"
        r.entryIds.shouldBeEmpty()
        r.amountMinor shouldBe -5_000L
        row shouldBe BookValueComparison.compareAll(emptyMap(), mapOf(abc to 5_000L)).single()
        DataGapWarnings.detect(emptyList(), engine.replay(emptyList())).shouldBeEmpty()
    }

    test("AC6 warnings coexist with an explained difference and add 0 to the sum of the reasons") {
        val roc = L06Activities.roc("roc", day(20), "XYZ", 2_000, seq = 3)
        val acts = ac1 + roc
        // The broker value is the ACB before the return of capital: the ROC explains the whole difference.
        val explained = mapOf(xyz to 10_000L)
        val row = rows(acts, explained).single { it.key == xyz }
        row.differenceCents shouldBe 8_000L - 10_000L
        row.reasons.first { it.ruleId == "ROC-REDUCES-ACB" }.entryIds shouldContain roc.entryId
        row.reasons.gaps().single().ruleId shouldBe noCost
        row.reasons.sumOf { it.amountMinor } shouldBe row.differenceCents

        // With the story's $1,100.00 book value the warning and the rule reasons both appear, and the sum is the same
        // as without the warning (the warning contributes 0).
        val withWarning = rows(acts, ac1Book).single { it.key == xyz }
        val without = plainRows(acts, ac1Book).single { it.key == xyz }
        withWarning.reasons.any { it.ruleId == "ROC-REDUCES-ACB" } shouldBe true
        withWarning.reasons.gaps().single().ruleId shouldBe noCost
        withWarning.reasons.sumOf { it.amountMinor } shouldBe without.reasons.sumOf { it.amountMinor }
        withWarning.reasons.filterNot { it.ruleId.startsWith("DATA-GAP-") } shouldBe without.reasons
    }

    test("AC6 no book value: a row listed as NO_BROKER_VALUE also carries the warning") {
        val row = rows(ac1, emptyMap()).single { it.key == xyz }
        row.bookValueCents shouldBe null
        row.differenceCents shouldBe null
        row.reasons.map { it.ruleId } shouldContainExactly listOf("NO_BROKER_VALUE", noCost)
        row.reasons.sumOf { it.amountMinor } shouldBe 0L
    }

    val csv = "security_id,book_value\nXYZ,1100.00\n"

    test("AC7 route: GET acb-vs-book returns the warning in the row's reasons with its entry ids") {
        testApplication {
            application { acbVsBookFromActivities({ _ -> ac1 }, csv) }
            val res = client.get("/v1/holdings/$tp/acb-vs-book")
            res.status shouldBe HttpStatusCode.OK
            val h =
                Json.parseToJsonElement(res.bodyAsText()).jsonObject.getValue("holdings").jsonArray
                    .map { it.jsonObject }.single { it.getValue("securityId").jsonPrimitive.content == "XYZ" }
            val gaps =
                h.getValue("reasons").jsonArray.map { it.jsonObject }
                    .filter { it.getValue("ruleId").jsonPrimitive.content.startsWith("DATA-GAP-") }
            val w = gaps.single()
            w.getValue("ruleId").jsonPrimitive.content shouldBe noCost
            w.getValue("text").jsonPrimitive.content shouldBe noCostText
            w.getValue("amountMinor").jsonPrimitive.content.toLong() shouldBe 0L
            w.getValue("entryIds").jsonArray.map { it.jsonPrimitive.content.toLong() } shouldBe listOf(1L)
        }
    }

    test("AC7 route: an over-sell also shows through the route (replaySuperficial path)") {
        val acts = listOf(buy(1, 10, 10_000), sell(2, 12, 13_000))
        testApplication {
            application { acbVsBookFromActivities({ _ -> acts }, "security_id,book_value\nXYZ,100.00\n") }
            val text = client.get("/v1/holdings/$tp/acb-vs-book").bodyAsText()
            text shouldContain oversell
            val h = Json.parseToJsonElement(text).jsonObject.getValue("holdings").jsonArray.single().jsonObject
            h.getValue("reasons").jsonArray.map { it.jsonObject }
                .single { it.getValue("ruleId").jsonPrimitive.content == oversell }
                .getValue("entryIds").jsonArray.map { it.jsonPrimitive.content.toLong() } shouldBe listOf(2L)
        }
    }

    test("AC7 UI: the reasons cell renders every reason's rule id and text (guard; any ruleId is listed)") {
        val tsx = SourceText.read("ui/src/AcbVsBook.tsx")
        tsx shouldContain "h.reasons.map"
        tsx shouldContain "r.ruleId"
        tsx shouldContain "r.text"
        // No allow-list of rule ids that would hide a DATA-GAP-* reason.
        (tsx.contains("filter(")) shouldBe false
    }
})
