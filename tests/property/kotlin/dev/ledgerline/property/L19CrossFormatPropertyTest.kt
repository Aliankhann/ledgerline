package dev.ledgerline.property

import dev.ledgerline.app.ImportPreview
import dev.ledgerline.calc.AcbEngine
import dev.ledgerline.calc.CaIta2026v1
import dev.ledgerline.calc.PostedActivity
import dev.ledgerline.calc.TaxYearReport
import dev.ledgerline.core.Activity
import dev.ledgerline.core.ActivityType
import dev.ledgerline.core.Currency
import dev.ledgerline.ingest.BrokerFormats
import dev.ledgerline.ingest.CanonicalCsv
import dev.ledgerline.testsupport.Activities
import dev.ledgerline.testsupport.L06Activities
import dev.ledgerline.testsupport.L19
import dev.ledgerline.testsupport.L19.normalized
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.property.Arb
import io.kotest.property.arbitrary.long
import io.kotest.property.checkAll
import java.time.LocalDate
import kotlin.random.Random

/**
 * L-19 AC4 cross-format consistency. Source: docs/stories/L-19 AC4. No expected value is written by hand: the same
 * generated trades are rendered as canonical CSV and as Broker B CSV, both are parsed, and everything downstream
 * (activities, pools, tax-year totals) must be identical. The generator is a seeded function so a failing seed replays.
 *
 * Generated: DEPOSIT, BUY, SELL (never more than held at that point, so invariant 7 holds in every set), WITHDRAWAL,
 * DIVIDEND; two securities; CAD and USD cash rows; shares at 6 decimals; amounts from 1 minor unit up to 20 million
 * dollars; commissions from 0 to 99.99; settlement dates present on half the rows; dates over 2025 and 2026.
 * Not generated: FX_CONVERSION, FEE, RETURN_OF_CAPITAL (Broker B has no action for them), TRANSFER_IN (covered by the
 * spec), and in-file repeats.
 */
class L19CrossFormatPropertyTest : FunSpec({
    val engine = AcbEngine(CaIta2026v1)
    val seeds = Arb.long()
    val d = L19.defaults

    fun act(
        type: ActivityType,
        day: LocalDate,
        amount: Long,
        fee: Long,
        sec: String? = null,
        qty: Long? = null,
    ): Activity = Activities.activity("k", type, day, amount, securityId = sec, quantity = qty, commissionMinor = fee)

    fun trades(
        seed: Long,
        account: String = L19.ACCOUNT,
    ): List<Activity> {
        val rnd = Random(seed)
        val held = mutableMapOf("SYN-AAA" to 0L, "SYN-BBB" to 0L)
        var day = LocalDate.of(2025, 1, 2)
        val out = mutableListOf<Activity>()
        repeat(rnd.nextInt(1, 40)) { i ->
            day = day.plusDays(rnd.nextLong(0, 12))
            val sec = held.keys.elementAt(rnd.nextInt(2))
            val amount = if (rnd.nextInt(10) == 0) rnd.nextLong(1, 2_000_000_000L) else rnd.nextLong(1, 500_000L)
            val fee = rnd.nextLong(0, 10_000L)
            val settle = if (rnd.nextBoolean()) day.plusDays(rnd.nextLong(0, 3)) else null
            val pick = rnd.nextInt(10)
            val a =
                when {
                    pick < 4 -> {
                        val q = rnd.nextLong(1, 300_000_000L)
                        held[sec] = held.getValue(sec) + q
                        act(ActivityType.BUY, day, amount, fee, sec, q)
                    }
                    pick < 7 && held.getValue(sec) > 0 -> {
                        val all = held.getValue(sec)
                        val q = if (rnd.nextBoolean()) all else rnd.nextLong(1, all + 1)
                        held[sec] = all - q
                        act(ActivityType.SELL, day, amount, fee, sec, q)
                    }
                    pick == 7 -> act(ActivityType.DEPOSIT, day, amount, fee)
                    pick == 8 -> act(ActivityType.WITHDRAWAL, day, amount, fee)
                    else -> act(ActivityType.DIVIDEND, day, amount, fee, sec)
                }
            val cash = a.securityId == null && rnd.nextBoolean()
            out +=
                a.copy(
                    idempotencyKey = "TP-0001-2025-%04d".format(i + 1),
                    settlementDate = settle,
                    currency = if (cash) Currency.USD else a.currency,
                    accountId = account,
                )
        }
        return out
    }

    fun posted(
        acts: List<Activity>,
        firstEntry: Long = 1L,
    ): List<PostedActivity> = acts.mapIndexed { i, a -> L06Activities.posted(a, firstEntry + i) }

    fun poolFigures(rows: List<PostedActivity>) =
        engine.replay(rows).pools.mapValues { (_, p) -> p.shares to p.totalAcb }

    test(
        "L-19 AC4 property: canonical and Broker B give identical activities, key aside (200 seeds)",
    ) {
        checkAll(200, seeds) { seed ->
            val acts = trades(seed)
            val canon = CanonicalCsv.parse(L19.renderCanonical(acts))
            val b = BrokerFormats.parse(L19.renderBrokerB(acts, L19.ACCOUNT), d)
            canon.rejections.shouldBeEmpty()
            canon.fileErrors.shouldBeEmpty()
            b.rejections.shouldBeEmpty()
            b.fileErrors.shouldBeEmpty()
            b.activities.size shouldBe canon.activities.size
            b.activities.map { it.normalized() } shouldBe canon.activities.map { it.normalized() }
            b.activities.map { it.idempotencyKey } shouldBe acts.indices.map { "BROKERB:${L19.ACCOUNT}:R${it + 1}" }
        }
    }

    test("L-19 AC4 property: identical ACB pools and identical tax-year totals for 2025 and 2026 (200 seeds)") {
        checkAll(200, seeds) { seed ->
            val acts = trades(seed)
            val canon = posted(CanonicalCsv.parse(L19.renderCanonical(acts)).activities)
            val b = posted(BrokerFormats.parse(L19.renderBrokerB(acts, L19.ACCOUNT), d).activities)
            poolFigures(b) shouldBe poolFigures(canon)
            for (year in listOf(2025, 2026)) {
                val tc = TaxYearReport.build(canon, L19.TP, year)
                val tb = TaxYearReport.build(b, L19.TP, year)
                tb.totals shouldBe tc.totals
                tb.rows.size shouldBe tc.rows.size
                tb.warnings.map { it.ruleId } shouldBe tc.warnings.map { it.ruleId }
            }
        }
    }

    test("L-19 AC4 property: the preview counts and row types and dates agree for the two renderings (100 seeds)") {
        checkAll(100, seeds) { seed ->
            val acts = trades(seed)
            val pc = ImportPreview.classify(L19.renderCanonical(acts), { null })
            val pb = ImportPreview.classify(L19.renderBrokerB(acts, L19.ACCOUNT), { null }, emptyList(), d)
            pb.rejected shouldBe 0
            listOf(pb.rowsRead, pb.new) shouldBe listOf(pc.rowsRead, pc.new)
            pb.rows.map { Triple(it.line, it.type, it.date) } shouldBe pc.rows.map { Triple(it.line, it.type, it.date) }
        }
    }

    test("L-19 AC4 property: two accounts, one per format, share one pool per security (100 seeds)") {
        checkAll(100, seeds) { seed ->
            val one = trades(seed)
            val canon = CanonicalCsv.parse(L19.renderCanonical(one)).activities
            val b = BrokerFormats.parse(L19.renderBrokerB(one, "TP-0001-NR2"), d).activities
            (canon + b).map { it.accountId }.toSet().size shouldBe 2
            val alone = engine.replay(posted(canon)).pools
            val both = engine.replay(posted(canon + b)).pools
            both.keys shouldBe alone.keys
            both.keys.all { it.taxpayerId == L19.TP } shouldBe true
            alone.forEach { (k, p) -> both.getValue(k).shares shouldBe 2 * p.shares }
            // ACB: two identical books pooled cannot differ in cost from the sum of their parts by more than rounding
            // of the finer unit; asserted only for the case with no sell, where no proportional split is made.
            if (one.none { it.activityType == ActivityType.SELL }) {
                alone.forEach { (k, p) -> both.getValue(k).totalAcb shouldBe 2 * p.totalAcb }
            }
        }
    }

    test("L-19 AC4 property: re-ordering Broker B rows keeps every key mapped to the same activity (100 seeds)") {
        checkAll(100, seeds) { seed ->
            val acts = trades(seed)
            val text = L19.renderBrokerB(acts, L19.ACCOUNT).lines()
            val shuffled =
                (
                    listOf(text.first()) +
                        text.drop(1).filter {
                            it.isNotBlank()
                        }.shuffled(Random(seed))
                ).joinToString("\n") + "\n"
            val inOrder = BrokerFormats.parse(text.joinToString("\n"), d).activities
            val moved = BrokerFormats.parse(shuffled, d).activities
            // Same rows in another order: each key maps to the same activity (the row position aside).
            moved.associateBy { it.idempotencyKey }.mapValues { it.value.copy(sourceSeq = 0) } shouldBe
                inOrder.associateBy { it.idempotencyKey }.mapValues { it.value.copy(sourceSeq = 0) }
        }
    }
})
