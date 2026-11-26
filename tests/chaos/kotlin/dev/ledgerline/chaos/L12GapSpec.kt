package dev.ledgerline.chaos

import dev.ledgerline.app.SeedLoader
import dev.ledgerline.calc.AcbEngine
import dev.ledgerline.calc.CaIta2026v1
import dev.ledgerline.calc.PoolConsumer
import dev.ledgerline.core.AccountType
import dev.ledgerline.core.ActivityType
import dev.ledgerline.testsupport.AppFx
import dev.ledgerline.testsupport.Integration
import dev.ledgerline.testsupport.L05Env
import dev.ledgerline.testsupport.L10
import dev.ledgerline.testsupport.PostgresLedger
import dev.ledgerline.testsupport.ProxiedPostgres
import dev.ledgerline.testsupport.RedpandaBroker
import dev.ledgerline.testsupport.SeedSupport
import dev.ledgerline.testsupport.awaitFullyConsumed
import dev.ledgerline.testsupport.consumerConfig
import dev.ledgerline.testsupport.inboxEventIds
import dev.ledgerline.testsupport.reconcileAndReport
import eu.rekawek.toxiproxy.model.ToxicDirection
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.comparables.shouldBeLessThan
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import java.time.Duration
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * Story L-12 part 2 (docs/stories/L-12-crash-recovery.md), the chaos scenarios the traceability gate found missing:
 * AC5 (flaky database connection through Toxiproxy) and AC8 (late correction while the consumer is mid-stream).
 * AC3, AC4, AC6, AC7 are in `dev.ledgerline.spec.L12CrashRecoverySpec`.
 *
 * Every test is tagged [Integration] (Docker: Postgres, Toxiproxy, Redpanda) and compiles without Docker; it was NOT
 * run where it was written. W42 is the seed 42 year (`SeedLoader`, 5 taxpayers, 2025), posted through the real
 * ingestion path. The oracle is `reconcile` (L-10 `ReconcileCommand`) exiting 0 with `drift=0`, run on a direct
 * connection that is never faulted. No tax value is expected from reasoning.
 *
 * Choices and limits:
 * - AC5 uses the story's placeholder values (500 ms latency for 30 s, then a connection reset every 2 s for 30 s).
 *   "Reset every 2 seconds" is done by adding a `reset_peer` toxic (timeout 0) for 300 ms every 2 s, which cuts every
 *   open connection at once, rather than a `reset_peer` with a 2000 ms timeout (that only cuts connections older than
 *   2 s, and the consumer's transactions are shorter). The test cannot see how many connections were cut.
 * - AC8: the correction is posted once the inbox holds a quarter of the events (the consumer is still running). If
 *   the consumer is faster than the 50 ms poll, it is only "mid-stream" in the sense that the reversal and replacement
 *   events arrive after other events were consumed; the test prints the inbox size at that moment.
 * - AC9: each scenario ends through `reconcileAndReport`, which prints `CHAOS scenario=<name> result=PASS|FAIL
 *   events=<n>`. AC3, AC4, AC6 and AC7 are not in the `chaos` package, so `:tests:chaosTest` does not run them
 *   (reported to the owner as a gap in AC9, not changed here).
 */
class L12GapSpec : FunSpec({

    val broker = RedpandaBroker()
    val pg = PostgresLedger()
    afterSpec {
        broker.close()
        pg.close()
    }
    val integration = setOf(Integration)
    val engine = AcbEngine(CaIta2026v1, AppFx.cache())

    test("L-12 AC5: W42 through a flaky Postgres connection (latency, then resets): reconcile exits 0, no event twice")
        .config(tags = integration) {
            ProxiedPostgres().use { px ->
                val db = px.freshDatabase()
                val e = L05Env(db.direct, broker, broker.freshTopic())
                SeedLoader.load(e.db.app, SeedSupport.SEED)
                val total = e.events().size
                check(total > 0) { "no events to consume" }

                e.draining {
                    PoolConsumer(db.proxiedApp, e.consumerConfig("l12-ac5"), engine, e.topic).use { c ->
                        c.start()
                        val toxics = px.toxiproxy.toxics()

                        val latency = toxics.latency("ac5-latency", ToxicDirection.DOWNSTREAM, 500)
                        Thread.sleep(Duration.ofSeconds(30).toMillis())
                        latency.remove()

                        val until = System.nanoTime() + Duration.ofSeconds(30).toNanos()
                        while (System.nanoTime() < until) {
                            val reset = toxics.resetPeer("ac5-reset", ToxicDirection.DOWNSTREAM, 0)
                            Thread.sleep(300)
                            reset.remove()
                            Thread.sleep(1_700)
                        }
                        // Toxics are removed; the consumer must now catch up without help.
                        e.awaitFullyConsumed(Duration.ofSeconds(180)) shouldBe true
                        c.stop()
                    }
                }

                val inbox = e.db.inboxEventIds()
                withClue("no eventId may appear twice, and every event must be applied") {
                    inbox.size shouldBe inbox.toSet().size
                    inbox shouldContainExactly e.events().map { it.eventId }.sorted()
                }
                e.reconcileAndReport("L-12 AC5 flaky database connection")
            }
        }

    test("L-12 AC8: a correction of a W42 buy dated 6 months earlier, posted while consuming: reconcile exits 0")
        .config(tags = integration) {
            run {
                val e = L10.env(pg, broker)
                SeedLoader.load(e.db.app, SeedSupport.SEED)
                val before = e.events().size

                val victim =
                    SeedSupport.expected()
                        .filter { it.accountType == AccountType.NON_REGISTERED && it.activityType == ActivityType.BUY }
                        .maxByOrNull { it.effectiveDate }
                        ?: error("the seed 42 year has no non-registered BUY to correct")
                val moved = maxOf(victim.effectiveDate.minusMonths(6), LocalDate.of(2025, 1, 1))
                moved shouldBeLessThan victim.effectiveDate
                val days = ChronoUnit.DAYS.between(moved, victim.effectiveDate)
                val replacement =
                    victim.copy(
                        idempotencyKey = "${victim.idempotencyKey}-C1",
                        effectiveDate = moved,
                        settlementDate = victim.settlementDate?.minusDays(days),
                    )
                val entryId =
                    e.db.query(
                        e.db.owner,
                        "SELECT entry_id FROM ledger.entry WHERE idempotency_key = '${victim.idempotencyKey}'",
                    ).single()[0]!!.toLong()

                e.draining {
                    PoolConsumer(e.db.app, e.consumerConfig("l12-ac8"), engine, e.topic).use { c ->
                        c.start()
                        val deadline = System.nanoTime() + Duration.ofSeconds(60).toNanos()
                        while (e.db.inboxEventIds().size < maxOf(1, before / 4) && System.nanoTime() < deadline) {
                            Thread.sleep(50)
                        }
                        println("L-12 AC8: correcting with ${e.db.inboxEventIds().size} of $before events consumed")
                        e.repo.correct(entryId, "${victim.idempotencyKey}-COR", "L-12 AC8 late correction", replacement)
                        e.awaitFullyConsumed(Duration.ofSeconds(120)) shouldBe true
                        c.stop()
                    }
                }

                e.events().size shouldBeGreaterThan before // the reversal and the replacement were published too
                e.reconcileAndReport("L-12 AC8 late correction during consumption")
            }
        }
})
