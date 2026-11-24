package dev.ledgerline.spec

import dev.ledgerline.calc.AcbEngine
import dev.ledgerline.calc.CaIta2026v1
import dev.ledgerline.calc.ExplainStatus
import dev.ledgerline.calc.Explanation
import dev.ledgerline.calc.ExplanationFooter
import dev.ledgerline.calc.HoldingExplanation
import dev.ledgerline.calc.PoolKey
import dev.ledgerline.calc.Reason
import dev.ledgerline.core.ActivityType
import dev.ledgerline.testsupport.Activities
import dev.ledgerline.testsupport.CraFixture
import dev.ledgerline.testsupport.L06Activities
import dev.ledgerline.testsupport.L17.cents
import dev.ledgerline.testsupport.L17.checkRunning
import dev.ledgerline.testsupport.L17.csvFor
import dev.ledgerline.testsupport.L17.explain
import dev.ledgerline.testsupport.L17.get
import dev.ledgerline.testsupport.L17.ids
import dev.ledgerline.testsupport.L17.long
import dev.ledgerline.testsupport.L17.parse
import dev.ledgerline.testsupport.L17.plainCents
import dev.ledgerline.testsupport.L17.reasonList
import dev.ledgerline.testsupport.L17.stepList
import dev.ledgerline.testsupport.L17.text
import dev.ledgerline.testsupport.SeedSupport
import dev.ledgerline.testsupport.SourceText
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.longs.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import java.time.LocalDate
import kotlin.random.Random

/**
 * L-17 explain a holding (docs/stories/L-17-explain-a-holding.md), pure function and route tests. PROVISIONAL: the
 * story is a Draft and OWNER-INPUT. The API contract Dev builds against is on `L17` in
 * `tests/support/.../L17.kt`; the UI guards (AC6, the UI parts of AC7 and AC8) are in `L17UiGuardSpec`.
 *
 * Provisional decisions followed: no portfolio-total card; the add-back step's target buy may be derived (only what
 * AC3 states is tested); no FX or missing-commission reasons. Tax numbers come only from
 * `tests/fixtures/cra/cra-xyz-gain.properties` (CRA XYZ example) and the share ratio of
 * `cra-ti-2004-0073011e5-partial-sl.properties` (CRA TI 2004-0073011E5); no dollar amount that exists only by fixture
 * assumption is asserted. Other numbers are story inputs (a 495-cent commission, a book value 1 or 777 cents off).
 */
class L17ExplainSpec : FunSpec({

    val engine = AcbEngine(CaIta2026v1)
    val tp = L06Activities.TAXPAYER
    val xyzF = CraFixture.load("cra-xyz-gain")
    val slF = CraFixture.load("cra-ti-2004-0073011e5-partial-sl")
    val xyzKey = PoolKey(tp, xyzF.security)
    val slKey = PoolKey(tp, slF.security)
    val xyzRows = xyzF.rows(2)
    val slRows = slF.rows(4)

    val commissionBuy =
        L06Activities.trade(
            "L17-commission-buy",
            ActivityType.BUY,
            xyzF.date("t1.date"),
            xyzF.security,
            xyzF.long("t1.shares.units"),
            xyzF.long("t1.gross.cents"),
            495,
            1,
        )

    // ---------------------------------------------------------------- AC1

    test("L-17 AC1: the CRA XYZ fixture gives 2 steps, BUY then SELL, with the fixture's running shares and ACB") {
        val e = explain(xyzRows, null, xyzKey)
        e.steps.size shouldBe 2
        val (t1, t2) = e.steps
        t1.type shouldBe ActivityType.BUY
        t1.taxDate shouldBe LocalDate.of(2024, 1, 15)
        t1.taxDate shouldBe xyzF.date("t1.date")
        t1.sharesDeltaUnits shouldBe 400_000_000L
        t1.costDeltaCents shouldBe 400_000L
        t1.runningSharesUnits shouldBe xyzF.long("t1.expected.shares.units")
        t1.runningAcbCents shouldBe xyzF.long("t1.expected.acb.cents")
        t1.runningAcbCents shouldBe 400_000L

        t2.type shouldBe ActivityType.SELL
        t2.taxDate shouldBe LocalDate.of(2025, 3, 13)
        t2.runningSharesUnits shouldBe xyzF.long("t2.expected.shares.units")
        t2.runningAcbCents shouldBe xyzF.long("t2.expected.acb.cents")
        t2.runningSharesUnits shouldBe 0L
        t2.runningAcbCents shouldBe 0L
        // The sale takes the whole cost out: deltas sum to the final ACB (rounding at the reporting boundary only).
        t2.sharesDeltaUnits shouldBe -400_000_000L
        t2.costDeltaCents shouldBe -400_000L
        e.acbCents shouldBe 0L
        e.steps.sumOf { it.costDeltaCents } shouldBe e.acbCents
    }

    test("L-17 AC1: each step carries its entry id, idempotency key, activity id, tax date, type and valid rule ids") {
        val e = explain(xyzRows, null, xyzKey)
        e.steps.map { it.entryId } shouldBe xyzRows.map { it.entryId }
        e.steps.map { it.activityId } shouldBe xyzRows.map { it.activityId }
        e.steps.map { it.idempotencyKey } shouldBe xyzRows.map { it.activity.idempotencyKey }
        e.steps.forEach {
            it.ruleIds.shouldNotBeEmpty()
            CaIta2026v1.ruleIds shouldContainAll it.ruleIds
        }
    }

    test("L-17 AC1: running totals and cost deltas are consistent, and deltas sum to the final ACB (SL fixture)") {
        val e = explain(slRows, plainCents(slRows, slKey), slKey)
        e.steps.checkRunning()
        e.steps.sumOf { it.costDeltaCents } shouldBe e.acbCents
        e.acbCents shouldBe cents(slRows, slKey)
        e.steps.last().runningAcbCents shouldBe e.acbCents
    }

    test("L-17 AC1: GET /v1/holdings/{taxpayer}/SYN-CRA-XYZ/explain returns the same 2 steps as JSON") {
        val (status, text) = get(xyzRows, csvFor(xyzF.security, null), tp, xyzF.security)
        status shouldBe HttpStatusCode.OK
        val b = parse(text)
        b.text("taxpayerId") shouldBe tp
        b.text("securityId") shouldBe "SYN-CRA-XYZ"
        val steps = b.stepList()
        steps.size shouldBe 2
        steps.map { it.text("type") } shouldBe listOf("BUY", "SELL")
        steps.map { it.text("taxDate") } shouldBe listOf("2024-01-15", "2025-03-13")
        steps.map { it.long("entryId") } shouldBe xyzRows.map { it.entryId }
        steps.map { it.text("idempotencyKey") } shouldBe xyzRows.map { it.activity.idempotencyKey }
        steps.map { it.long("sharesDeltaUnits") } shouldBe listOf(400_000_000L, -400_000_000L)
        steps.map { it.long("costDeltaCents") } shouldBe listOf(400_000L, -400_000L)
        steps.map { it.long("runningSharesUnits") } shouldBe listOf(400_000_000L, 0L)
        steps.map { it.long("runningAcbCents") } shouldBe listOf(400_000L, 0L)
        steps.forEach { s ->
            val ids = s.getValue("ruleIds").jsonArray.map { it.jsonPrimitive.content }
            ids.shouldNotBeEmpty()
            CaIta2026v1.ruleIds shouldContainAll ids
        }
    }

    test("L-17 AC1: Main.kt wires the explain route through explainFromActivities") {
        val main = SourceText.withoutComments(SourceText.read("app/src/main/kotlin/dev/ledgerline/app/Main.kt"))
        main shouldContain "explainFromActivities"
    }

    // ---------------------------------------------------------------- AC2

    fun checkSumRule(e: Explanation) {
        val diff = requireNotNull(e.differenceCents)
        e.reasons.sumOf { it.amountMinor } shouldBe diff
        val stepEntries = e.steps.map { it.entryId }.toSet()
        e.reasons.filter { it.amountMinor != 0L }.forEach { r ->
            r.entryIds.shouldNotBeEmpty()
            r.entryIds.any { it in stepEntries } shouldBe true
        }
    }

    test("L-17 AC2: XYZ buy with commission and a book value 1 cent below ACB: reasons sum to the difference") {
        val rows = listOf(commissionBuy)
        val acb = cents(rows, xyzKey)
        val e = explain(rows, acb - 1, xyzKey)
        e.acbCents shouldBe acb
        e.differenceCents shouldBe 1L
        checkSumRule(e)
        val buyEntry = commissionBuy.entryId
        e.reasons.filter { it.ruleId == "COST-BUY-COMMISSION" || it.ruleId == "UNEXPLAINED" }
            .any { buyEntry in it.entryIds } shouldBe true
    }

    test("L-17 AC2: the same holding over HTTP: reasons sum to differenceCents and cite step entry ids") {
        val acb = cents(listOf(commissionBuy), xyzKey)
        val (status, text) = get(listOf(commissionBuy), csvFor(xyzF.security, acb - 1), tp, xyzF.security)
        status shouldBe HttpStatusCode.OK
        val b = parse(text)
        b.long("differenceCents") shouldBe 1L
        b.reasonList().sumOf { it.long("amountMinor") } shouldBe 1L
        val stepEntries = b.stepList().map { it.long("entryId") }.toSet()
        b.reasonList().filter { it.long("amountMinor") != 0L }.forEach { r ->
            r.ids("entryIds").shouldNotBeEmpty()
            r.ids("entryIds").any { it in stepEntries } shouldBe true
        }
    }

    test("L-17 AC2: superficial-loss fixture with the plain-replay book value: the add-back reason cites the sale") {
        val e = explain(slRows, plainCents(slRows, slKey), slKey)
        e.differenceCents shouldNotBe 0L
        checkSumRule(e)
        e.reasons.single { it.ruleId == "SL-ADDBACK-TARGET" }.entryIds shouldContain slRows[2].entryId
    }

    test("L-17 AC2: a book-value-free holding has a null difference and a non-cent NO_BROKER_VALUE reason") {
        val e = explain(xyzRows, null, xyzKey)
        e.bookValueCents shouldBe null
        e.differenceCents shouldBe null
        e.status shouldBe ExplainStatus.NO_BOOK_VALUE
        e.reasons.sumOf { it.amountMinor } shouldBe 0L
    }

    // ---------------------------------------------------------------- AC3

    test("L-17 AC3: the TI 2004-0073011E5 fixture has one SL-ADDBACK-TARGET step on the sale with its target buys") {
        val replay = engine.replaySuperficial(slRows)
        val sale = replay.sales.single { it.activityId == slF.id("t3") }
        val byActivity = slRows.associateBy { it.activityId }
        val targets = sale.triggers.map { byActivity.getValue(it.activityId).entryId }

        val e = explain(slRows, null, slKey)
        val step = e.steps.single { "SL-ADDBACK-TARGET" in it.ruleIds }
        CaIta2026v1.ruleIds shouldContain "SL-ADDBACK-TARGET"
        step.entryId shouldBe slRows[2].entryId
        step.sharesDeltaUnits shouldBe 0L
        // The step is dated at one of its targets (the story: "the TARGET's tax date").
        targets.map { id -> slRows.single { it.entryId == id }.activity.effectiveDate } shouldContain step.taxDate
        val sl = requireNotNull(step.superficialLoss)
        sl.saleEntryId shouldBe slRows[2].entryId
        sl.targetEntryIds.toSet() shouldBe targets.toSet()
        sl.targetEntryIds.shouldNotBeEmpty()
        // The target buys are t2 and t4 of the fixture (P = 20 shares): both are steps of the timeline.
        e.steps.map { it.entryId } shouldContainAll sl.targetEntryIds

        // denied x S = loss x least(S, P, B): the fixture's ratio 20/50, never a dollar amount.
        sl.lossCents shouldBeGreaterThan 0L
        sl.deniedCents shouldBeGreaterThan 0L
        Math.multiplyExact(sl.deniedCents, slF.long("expected.fraction.denominator.units")) shouldBe
            Math.multiplyExact(sl.lossCents, slF.long("expected.fraction.numerator.units"))
        sl.addBackCents shouldBe sl.deniedCents
        step.costDeltaCents shouldBe sl.addBackCents
    }

    test("L-17 AC3: only the add-back step has a superficialLoss detail, and the SELL step itself is still listed") {
        val e = explain(slRows, null, slKey)
        e.steps.filter { it.superficialLoss != null }.size shouldBe 1
        e.steps.filter { it.entryId == slRows[2].entryId }.map { it.type }.toSet() shouldBe setOf(ActivityType.SELL)
        e.steps.filter { it.entryId == slRows[2].entryId }.size shouldBe 2
    }

    test("L-17 AC3: over HTTP the add-back step carries the sale entry, the target entries and the 20/50 ratio") {
        val (status, text) = get(slRows, csvFor(slF.security, null), tp, slF.security)
        status shouldBe HttpStatusCode.OK
        val step =
            parse(text).stepList().single { s ->
                s.getValue("ruleIds").jsonArray.map { it.jsonPrimitive.content }.contains("SL-ADDBACK-TARGET")
            }
        step.long("entryId") shouldBe slRows[2].entryId
        val sl = step.getValue("superficialLoss").jsonObject
        sl.long("saleEntryId") shouldBe slRows[2].entryId
        sl.ids("targetEntryIds").shouldNotBeEmpty()
        slRows.map { it.entryId } shouldContainAll sl.ids("targetEntryIds")
        sl.long("lossCents") shouldBeGreaterThan 0L
        Math.multiplyExact(sl.long("deniedCents"), 50L) shouldBe Math.multiplyExact(sl.long("lossCents"), 20L)
        sl.long("addBackCents") shouldBe sl.long("deniedCents")
    }

    // ---------------------------------------------------------------- AC4

    val ghost = PoolKey(tp, "SYN-NOPE")

    test("L-17 AC4: unknown taxpayer, unknown security and an empty activity list explain to null") {
        HoldingExplanation.explain(emptyList(), null, xyzKey) shouldBe null
        HoldingExplanation.explain(xyzRows, null, ghost) shouldBe null
        HoldingExplanation.explain(xyzRows, null, PoolKey("TP-9999", xyzF.security)) shouldBe null
    }

    test("L-17 AC4: HTTP 404 names the taxpayer and the security, for an unknown taxpayer and an unknown security") {
        val csv = csvFor(xyzF.security, null)
        val (s1, t1) = get(xyzRows, csv, "TP-9999", xyzF.security)
        s1 shouldBe HttpStatusCode.NotFound
        t1 shouldContain "TP-9999"
        t1 shouldContain xyzF.security

        val (s2, t2) = get(xyzRows, csv, tp, "SYN-NOPE")
        s2 shouldBe HttpStatusCode.NotFound
        t2 shouldContain tp
        t2 shouldContain "SYN-NOPE"
    }

    val noCost = "DATA-GAP-TRANSFER-NO-COST"
    val oversell = "DATA-GAP-OVERSELL"
    val warnKey = PoolKey(tp, "XYZ")
    val transferOnly =
        listOf(
            L06Activities.posted(
                Activities.activity(
                    key = "k7",
                    type = ActivityType.TRANSFER_IN,
                    date = LocalDate.of(2025, 1, 10),
                    amountMinor = 0L,
                    securityId = "XYZ",
                    quantity = 100 * Activities.SHARE,
                    sourceSeq = 7,
                ),
                7,
            ),
        )
    val oversellOnly =
        listOf(
            L06Activities.posted(
                Activities.activity(
                    key = "k8",
                    type = ActivityType.SELL,
                    date = LocalDate.of(2025, 1, 10),
                    amountMinor = 5_000L,
                    securityId = "XYZ",
                    quantity = 5 * Activities.SHARE,
                    sourceSeq = 8,
                ),
                8,
            ),
        )

    test("L-17 AC4: a holding with only an L-16 warning has no steps and lists the warning with its entry ids") {
        listOf(transferOnly to noCost, oversellOnly to oversell).forEach { (rows, rule) ->
            val e = explain(rows, null, warnKey)
            e.steps.shouldBeEmpty()
            val w = e.reasons.single { it.ruleId == rule }
            w.entryIds shouldBe listOf(rows.single().entryId)
            w.amountMinor shouldBe 0L
            e.status shouldBe ExplainStatus.DATA_GAP
        }
    }

    test("L-17 AC4: HTTP 200 for a warning-only holding: empty steps and the warning with its entry ids") {
        listOf(transferOnly to noCost, oversellOnly to oversell).forEach { (rows, rule) ->
            val (status, text) = get(rows, csvFor("XYZ", null), tp, "XYZ")
            status shouldBe HttpStatusCode.OK
            val b = parse(text)
            b.getValue("steps") shouldBe JsonArray(emptyList())
            b.reasonList().single { it.text("ruleId") == rule }.ids("entryIds") shouldBe listOf(rows.single().entryId)
            b.text("status") shouldBe "DATA_GAP"
        }
    }

    // ---------------------------------------------------------------- AC5

    test("L-17 AC5: explaining the same activities twice, or in shuffled arrival order, gives equal explanations") {
        val acts = slRows + L06Activities.roc("L17-roc", LocalDate.of(2003, 8, 1), slF.security, 1_000, 5)
        val book = plainCents(acts, slKey) + 3
        val first = explain(acts, book, slKey)
        explain(acts, book, slKey) shouldBe first
        explain(acts.reversed(), book, slKey) shouldBe first
        (1..12).forEach { seed -> explain(acts.shuffled(Random(seed)), book, slKey) shouldBe first }
        first.steps.map { it.taxDate } shouldBe first.steps.map { it.taxDate }.sorted()
    }

    test("L-17 AC5: steps on the same tax date follow the source sequence, not the ledger entry order") {
        val day = LocalDate.of(2025, 5, 5)
        // Entry ids are the arrival order and are the REVERSE of the source sequence.
        val second =
            L06Activities.posted(
                Activities.activity(
                    "k-second",
                    ActivityType.BUY,
                    day,
                    2_000,
                    securityId = "SYN-ORD",
                    quantity = 2 * Activities.SHARE,
                    sourceSeq = 2,
                ),
                2001,
            )
        val first =
            L06Activities.posted(
                Activities.activity(
                    "k-first",
                    ActivityType.BUY,
                    day,
                    1_000,
                    securityId = "SYN-ORD",
                    quantity = Activities.SHARE,
                    sourceSeq = 1,
                ),
                2002,
            )
        val key = PoolKey(tp, "SYN-ORD")
        listOf(listOf(second, first), listOf(first, second)).forEach { arrival ->
            explain(arrival, null, key).steps.map { it.entryId } shouldBe listOf(2002L, 2001L)
        }
    }

    test("L-17 AC5: the HTTP body is byte-identical on a repeat and for every shuffled arrival order") {
        val acts = slRows + L06Activities.roc("L17-roc", LocalDate.of(2003, 8, 1), slF.security, 1_000, 5)
        val csv = csvFor(slF.security, plainCents(acts, slKey) + 3)
        val (status, base) = get(acts, csv, tp, slF.security)
        status shouldBe HttpStatusCode.OK
        get(acts, csv, tp, slF.security).second shouldBe base
        get(acts.reversed(), csv, tp, slF.security).second shouldBe base
        (1..6).forEach { seed -> get(acts.shuffled(Random(seed)), csv, tp, slF.security).second shouldBe base }
    }

    // ---------------------------------------------------------------- AC7

    test("L-17 AC7: a difference no rule explains is returned as an UNEXPLAINED reason, never dropped or folded") {
        val acb = cents(xyzRows.take(1), xyzKey)
        val e = explain(xyzRows.take(1), acb + 777, xyzKey)
        e.differenceCents shouldBe -777L
        val u = e.reasons.single { it.ruleId == "UNEXPLAINED" }
        u.amountMinor shouldBe -777L
        u.entryIds.shouldNotBeEmpty()
        e.reasons.filter { it.ruleId != "UNEXPLAINED" }.sumOf { it.amountMinor } shouldBe 0L
        e.reasons.sumOf { it.amountMinor } shouldBe -777L
        e.unexplainedCents shouldBe -777L
        e.fullyExplained shouldBe false
        e.status shouldBe ExplainStatus.DISCREPANCY
    }

    test("L-17 AC7: over HTTP the UNEXPLAINED reason and unexplainedCents are in the body") {
        val acb = cents(xyzRows.take(1), xyzKey)
        val (status, text) = get(xyzRows.take(1), csvFor(xyzF.security, acb + 777), tp, xyzF.security)
        status shouldBe HttpStatusCode.OK
        val b = parse(text)
        b.reasonList().single { it.text("ruleId") == "UNEXPLAINED" }.long("amountMinor") shouldBe -777L
        b.long("unexplainedCents") shouldBe -777L
        b.text("status") shouldBe "DISCREPANCY"
    }

    test(
        "L-17 AC7: seed-42 synthetic year: every holding either has no UNEXPLAINED amount or shows it (3 book values)",
    ) {
        val posted =
            SeedSupport.expected().mapIndexed { i, a -> L06Activities.posted(a, i + 1L) }
                .groupBy { it.activity.taxpayerId }
        var checked = 0
        posted.forEach { (taxpayer, acts) ->
            acts.mapNotNull { it.activity.securityId }.distinct().sorted().forEach { sec ->
                val key = PoolKey(taxpayer, sec)
                val base = HoldingExplanation.explain(acts, null, key) ?: return@forEach
                listOf(null, base.acbCents, base.acbCents + 137).forEach { book ->
                    val e = requireNotNull(HoldingExplanation.explain(acts, book, key))
                    val unexplained = e.reasons.filter { it.ruleId == "UNEXPLAINED" }
                    if (e.unexplainedCents != 0L) {
                        unexplained.sumOf { it.amountMinor } shouldBe e.unexplainedCents
                    }
                    if (unexplained.isEmpty()) e.unexplainedCents shouldBe 0L
                    if (e.differenceCents != null) e.reasons.sumOf { it.amountMinor } shouldBe e.differenceCents
                    checked++
                }
            }
        }
        checked shouldBeGreaterThan 0
    }

    // ---------------------------------------------------------------- AC8

    fun reason(
        rule: String,
        amount: Long,
        vararg entries: Long,
    ) = Reason(
        rule,
        "text of $rule",
        entries.toList(),
        amount,
    )

    test("L-17 AC8: difference 100 with reasons 60 + 40 is Explained, fully explained, N = 2") {
        val f =
            ExplanationFooter.of(
                100,
                listOf(reason("COST-BUY-COMMISSION", 60, 1), reason("ROC-REDUCES-ACB", 40, 2)),
            )
        f.status shouldBe ExplainStatus.EXPLAINED
        f.fullyExplained shouldBe true
        f.adjustmentCount shouldBe 2
        f.unexplainedCents shouldBe 0L
    }

    test("L-17 AC8: difference 100 with reasons 60 + UNEXPLAINED 40 is a Discrepancy with 40 cents unexplained") {
        val f = ExplanationFooter.of(100, listOf(reason("COST-BUY-COMMISSION", 60, 1), reason("UNEXPLAINED", 40, 1)))
        f.status shouldBe ExplainStatus.DISCREPANCY
        f.fullyExplained shouldBe false
        f.unexplainedCents shouldBe 40L
    }

    test("L-17 AC8: reasons that do not add up to the difference are never called fully explained") {
        val short = ExplanationFooter.of(100, listOf(reason("COST-BUY-COMMISSION", 60, 1)))
        short.fullyExplained shouldBe false
        short.unexplainedCents shouldBe 40L
        short.status shouldNotBe ExplainStatus.EXPLAINED
        short.status shouldNotBe ExplainStatus.MATCHES

        // An UNEXPLAINED reason, even if the amounts happen to add up, is not "fully explained".
        val flagged = ExplanationFooter.of(100, listOf(reason("UNEXPLAINED", 100, 1)))
        flagged.fullyExplained shouldBe false
        flagged.status shouldBe ExplainStatus.DISCREPANCY
        flagged.unexplainedCents shouldBe 100L

        // Negative differences use the same integer rule.
        ExplanationFooter.of(-70, listOf(reason("ROC-REDUCES-ACB", -70, 1))).status shouldBe ExplainStatus.EXPLAINED
        ExplanationFooter.of(-70, listOf(reason("UNEXPLAINED", -70, 1))).unexplainedCents shouldBe -70L
    }

    test("L-17 AC8: difference 0 with no reasons is Matches; zero-amount warnings are not adjustments") {
        val m = ExplanationFooter.of(0, emptyList())
        m.status shouldBe ExplainStatus.MATCHES
        m.fullyExplained shouldBe true
        m.adjustmentCount shouldBe 0

        val withWarning =
            ExplanationFooter.of(
                100,
                listOf(reason("COST-BUY-COMMISSION", 60, 1), reason("ROC-REDUCES-ACB", 40, 2), reason(oversell, 0, 3)),
            )
        withWarning.fullyExplained shouldBe true
        withWarning.adjustmentCount shouldBe 2
        withWarning.unexplainedCents shouldBe 0L
    }

    test("L-17 AC8: explain() reports Matches, Explained and Discrepancy from real holdings, one status each") {
        val xyz1 = xyzRows.take(1)
        val acb = cents(xyz1, xyzKey)
        val matches = explain(xyz1, acb, xyzKey)
        matches.status shouldBe ExplainStatus.MATCHES
        matches.unexplainedCents shouldBe 0L

        val explained = explain(slRows, plainCents(slRows, slKey), slKey)
        explained.status shouldBe ExplainStatus.EXPLAINED
        explained.fullyExplained shouldBe true
        explained.unexplainedCents shouldBe 0L
        explained.adjustmentCount shouldBe explained.reasons.count { it.amountMinor != 0L }
        explained.adjustmentCount shouldBeGreaterThan 0

        val disc = explain(xyz1, acb + 5, xyzKey)
        disc.status shouldBe ExplainStatus.DISCREPANCY
        disc.fullyExplained shouldBe false

        ExplainStatus.entries.map { it.name }.none { it.contains("UNRECONCILED") } shouldBe true
    }
})
