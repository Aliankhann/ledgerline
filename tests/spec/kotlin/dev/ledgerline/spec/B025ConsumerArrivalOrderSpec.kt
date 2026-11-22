package dev.ledgerline.spec

import dev.ledgerline.calc.AcbEngine
import dev.ledgerline.calc.CaIta2026v1
import dev.ledgerline.calc.CalcMigrations
import dev.ledgerline.calc.InboxState
import dev.ledgerline.calc.PoolConsumer
import dev.ledgerline.calc.PoolKey
import dev.ledgerline.calc.PoolStore
import dev.ledgerline.calc.PostedActivity
import dev.ledgerline.calc.Reconciler
import dev.ledgerline.core.Activity
import dev.ledgerline.core.ActivityType
import dev.ledgerline.ingest.CanonicalCsv
import dev.ledgerline.ingest.SyntheticGenerator
import dev.ledgerline.testsupport.Activities
import dev.ledgerline.testsupport.Integration
import dev.ledgerline.testsupport.PostgresLedger
import dev.ledgerline.testsupport.RedpandaBroker
import dev.ledgerline.testsupport.consumerConfig
import dev.ledgerline.testsupport.l05Env
import dev.ledgerline.testsupport.pollUntilInbox
import dev.ledgerline.testsupport.postAndPublish
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import java.time.LocalDate

/**
 * B-025: the consumer's live pool is built in arrival order, a replay in canonical order, so they drift whenever
 * arrival order and tax-date order differ. Source: CLAUDE.md invariants 5 and 6 (replay is the reference) and
 * DATE-BASIS (tax date = settlement date when present, else trade date; owner decision C6/C7, `CaIta2026v1`).
 * No tax value here comes from reasoning: the expected pools are whatever `AcbEngine.replay` (the reference) gives,
 * and the single hand-written number (900.00) is only a check that the reference orders by tax date.
 *
 * The pure tests drive [InboxState], which is the consumer's apply step without Postgres and Kafka: it calls the same
 * `applyToPool` as `PoolConsumer.applyAndStore`. The Integration test drives the real `PoolConsumer`.
 *
 * Minimal repro: a RETURN_OF_CAPITAL (trade date 06-01, settles 06-03) delivered before a BUY (trade date 06-01,
 * settles 06-02) of the same security. By tax date the BUY comes first, so the ROC reduces its ACB; in arrival order
 * the ROC hits an empty pool (absorbs nothing) and the BUY's ACB stays whole.
 */
class B025ConsumerArrivalOrderSpec : FunSpec({
    val pg = PostgresLedger()
    val broker = RedpandaBroker()
    afterSpec {
        pg.close()
        broker.close()
    }
    val engine = AcbEngine(CaIta2026v1)
    val key = PoolKey("TP-0001", "SYN-AAA")

    /** ROC of 100.00, settles 06-03. */
    fun roc() =
        Activities.activity(
            "TP-0001-B25-ROC",
            ActivityType.RETURN_OF_CAPITAL,
            LocalDate.of(2025, 6, 1),
            amountMinor = 10_000L,
            securityId = "SYN-AAA",
        ).copy(settlementDate = LocalDate.of(2025, 6, 3))

    /** BUY of 10 shares for 1000.00 with no commission, settles 06-02. */
    fun buy() =
        Activities.activity(
            "TP-0001-B25-BUY",
            ActivityType.BUY,
            LocalDate.of(2025, 6, 1),
            amountMinor = 100_000L,
            securityId = "SYN-AAA",
            quantity = 10L * Activities.SHARE,
        ).copy(settlementDate = LocalDate.of(2025, 6, 2))

    fun posted(activities: List<Activity>) = activities.mapIndexed { i, a -> PostedActivity("act-$i", i + 1L, a) }

    fun live(history: List<PostedActivity>): Map<PoolKey, Pair<Long, Long>> {
        val inbox = InboxState(engine)
        history.forEachIndexed { i, p -> inbox.deliver(i + 1L, p) }
        return inbox.pools.mapValues { (_, p) -> p.shares to p.totalAcb }
    }

    fun replay(history: List<PostedActivity>): Map<PoolKey, Pair<Long, Long>> =
        engine.replay(history).pools.mapValues { (_, p) -> p.shares to p.totalAcb }

    test("B-025: the reference orders the minimal pair by tax date, so the ROC reduces the BUY's ACB to 900.00") {
        // 10 shares; ACB 1000.00 - 100.00 = 900.00 at 10^-4 cent per unit.
        replay(posted(listOf(roc(), buy())))[key] shouldBe (10L * Activities.SHARE to 900_000_000L)
    }

    test("B-025: live pool equals replay when a ROC is delivered before a BUY that settles earlier") {
        val history = posted(listOf(roc(), buy()))
        live(history) shouldBe replay(history)
    }

    test("B-025: the live pool does not depend on arrival order (invariant 6)") {
        val roc = roc()
        val buy = buy()
        live(posted(listOf(roc, buy))) shouldBe live(posted(listOf(buy, roc)))
    }

    test("B-025: seed 42 in generator order: live pools equal replay (TP-0001 SYN-CCC drifted in CI)") {
        val parsed = CanonicalCsv.parse(SyntheticGenerator.generate(42, 5, 2025))
        parsed.rejections.shouldBeEmpty()
        parsed.fileErrors.shouldBeEmpty()
        val history = posted(parsed.activities)
        val replayed = replay(history)
        val delivered = live(history)
        // CI observed: TP-0001/SYN-CCC live 15633190000 vs replay 15234050000 (diff 399140000 = ROC 399.14 x 10^4).
        delivered[PoolKey("TP-0001", "SYN-CCC")] shouldBe replayed[PoolKey("TP-0001", "SYN-CCC")]
        delivered shouldBe replayed
    }

    test("B-025 (integration): PoolConsumer pools show zero drift after the minimal pair arrives out of tax-date order")
        .config(tags = setOf(Integration)) {
            val e = pg.l05Env(broker)
            CalcMigrations.migrate(e.db.owner)
            val history = e.postAndPublish(listOf(roc(), buy()))
            PoolConsumer(e.db.app, e.consumerConfig("b025"), engine, e.topic).use { it.pollUntilInbox(e.db, 2) }
            Reconciler(engine).reconcile(PoolStore(e.db.app).pools(), history).drift.shouldBeEmpty()
        }
})
