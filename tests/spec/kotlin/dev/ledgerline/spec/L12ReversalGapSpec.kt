package dev.ledgerline.spec

import dev.ledgerline.calc.AcbEngine
import dev.ledgerline.calc.CaIta2026v1
import dev.ledgerline.calc.CalcMigrations
import dev.ledgerline.calc.PoolConsumer
import dev.ledgerline.calc.PoolStore
import dev.ledgerline.calc.Reconciler
import dev.ledgerline.core.ActivityType
import dev.ledgerline.testsupport.Activities
import dev.ledgerline.testsupport.AppFx
import dev.ledgerline.testsupport.Integration
import dev.ledgerline.testsupport.PostgresLedger
import dev.ledgerline.testsupport.RedpandaBroker
import dev.ledgerline.testsupport.consumerConfig
import dev.ledgerline.testsupport.l05Env
import dev.ledgerline.testsupport.pollUntilInbox
import dev.ledgerline.testsupport.postAndPublish
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import java.time.Duration
import java.time.LocalDate

/**
 * L-12 phase B round 1: reversal attack (bug B-024). Under C19 a replay skips a reversed pair, so after a reversal
 * the live pools must equal a replay of the history without the reversed entry. Source: CLAUDE.md invariants 4 and 5.
 *
 * The Integration test below (post, consume, reverse, consume, Reconciler zero drift) is the sole authority for B-024.
 * There is no DB-free production entry point, so a pure case could only exercise a test helper and was removed.
 */
class L12ReversalGapSpec : FunSpec({
    val pg = PostgresLedger()
    val broker = RedpandaBroker()
    afterSpec {
        pg.close()
        broker.close()
    }
    val engine = AcbEngine(CaIta2026v1, AppFx.cache())
    val reconciler = Reconciler(engine)

    fun buy() =
        Activities.activity(
            "TP-0001-REV-BUY",
            ActivityType.BUY,
            LocalDate.of(2025, 2, 3),
            amountMinor = 100_000L,
            securityId = "SYN-AAA",
            quantity = 100L * Activities.SHARE,
            sourceSeq = 1L,
        )

    fun sell() =
        Activities.activity(
            "TP-0001-REV-SELL",
            ActivityType.SELL,
            LocalDate.of(2025, 3, 3),
            amountMinor = 60_000L,
            securityId = "SYN-AAA",
            quantity = 40L * Activities.SHARE,
            sourceSeq = 2L,
        )

    test("L-12 reversal (integration): consumer pools show zero drift after a reversal of the SELL")
        .config(tags = setOf(Integration)) {
            val e = pg.l05Env(broker)
            CalcMigrations.migrate(e.db.owner)
            val b = buy()
            val s = sell()
            val history = e.postAndPublish(listOf(b, s))
            PoolConsumer(e.db.app, e.consumerConfig("l12-rev"), engine, e.topic).use { c ->
                c.pollUntilInbox(e.db, 2)
                e.repo.reverse(history[1].entryId, "TP-0001-REV-SELL-R", "attack: reverse the sell")
                e.draining { check(e.awaitDelivered(Duration.ofSeconds(60)).isEmpty()) }
                c.pollUntilInbox(e.db, 3)
            }
            reconciler.reconcile(PoolStore(e.db.app).pools(), listOf(history[0])).drift.shouldBeEmpty()
        }
})
